package io.infranexum.itam.asset.application;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import io.infranexum.itam.asset.domain.AssetCustodyEventType;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetNotFoundException;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.domain.AssetValue;
import io.infranexum.itam.asset.ports.AssetFeaturePolicy;
import io.infranexum.itam.asset.ports.AssetIdempotencyRepository;
import io.infranexum.itam.asset.ports.AssetOperationalReadinessPolicy;
import io.infranexum.itam.asset.ports.AssetReferencePolicy;
import io.infranexum.itam.asset.ports.AssetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PGM-07-E02 use cases with atomic custody history, outbox and command idempotence. */
public final class AssetApplicationService {
    private static final ContractVersion EVENT_VERSION = ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE = new EventSource("infranexum.itam.asset");

    private final AssetRepository assets;
    private final AssetIdempotencyRepository idempotency;
    private final AssetFeaturePolicy features;
    private final AssetReferencePolicy references;
    private final AssetOperationalReadinessPolicy readiness;
    private final TransactionalEventStore events;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AssetApplicationService(
            AssetRepository assets, AssetIdempotencyRepository idempotency, AssetFeaturePolicy features,
            AssetReferencePolicy references, AssetOperationalReadinessPolicy readiness,
            TransactionalEventStore events, UuidV7Generator ids, Clock clock) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.features = Objects.requireNonNull(features, "features");
        this.references = Objects.requireNonNull(references, "references");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.events = Objects.requireNonNull(events, "events");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Asset create(CreateAssetCommand command, AssetCommandContext context) {
        Objects.requireNonNull(command, "command"); Objects.requireNonNull(context, "context"); requireEnabled();
        AssetType type = AssetType.parse(command.assetType());
        AssetValue value = new AssetValue(command.acquisitionValue(), command.currencyCode());
        if (command.acquisitionDate().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("acquisitionDate cannot be in the future");
        }
        references.validateCanonicalObject(command.rsotObjectId(), command.owningOrganizationId());
        if (command.owningSubdivisionId() != null) references.validateSubdivision(command.owningOrganizationId(), command.owningSubdivisionId());
        if (command.acquiredFromPartnerId() != null) {
            references.validateAcquisitionPartner(command.acquiredFromPartnerId(), command.owningOrganizationId(), command.acquisitionDate());
        }
        Asset prototype = Asset.acquired(ids.next(), command.rsotObjectId(), type, command.owningOrganizationId(),
                command.owningSubdivisionId(), command.acquisitionDate(), value, command.acquiredFromPartnerId(),
                context.actorId(), context.reason(), clock.instant());
        String fingerprint = fingerprint("acquire", command.rsotObjectId(), type, command.owningOrganizationId(),
                command.owningSubdivisionId(), command.acquisitionDate(), value, command.acquiredFromPartnerId());
        return execute(transaction -> {
            Optional<AssetIdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) return replay(prior.orElseThrow(), fingerprint, "acquire");
            if (assets.count() >= features.assetLimit()) throw new AssetQuotaException();
            if (assets.existsByRsotObjectId(command.rsotObjectId())) {
                throw new AssetConflictException("ITAM_ASSET_RSOT_CONFLICT", "RSOT canonical object is already linked to an ITAM asset");
            }
            AssetCustodyEvent custody = custody(null, prototype, AssetCustodyEventType.ACQUIRED, context);
            assets.insert(prototype, custody);
            transaction.append(event("itam.asset.acquired.v1", prototype, context));
            idempotency.insert(new AssetIdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "acquire", prototype.id(), prototype.createdAt()));
            return prototype;
        });
    }

    public Asset receive(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "receive", AssetCustodyEventType.RECEIVED,
                "itam.asset.received.v1", false, asset -> asset.receive(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset stock(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "stock", AssetCustodyEventType.STOCKED,
                "itam.asset.stocked.v1", true, asset -> asset.stock(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset assign(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "assign", AssetCustodyEventType.ASSIGNED,
                "itam.asset.assigned.v1", true, asset -> asset.assign(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset deploy(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "deploy", AssetCustodyEventType.DEPLOYED,
                "itam.asset.deployed.v1", true, asset -> asset.deploy(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset transfer(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "transfer", AssetCustodyEventType.TRANSFERRED,
                "itam.asset.transferred.v1", false, asset -> asset.transfer(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset startMaintenance(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "maintenance-start", AssetCustodyEventType.MAINTENANCE_STARTED,
                "itam.asset.maintenance_started.v1", false,
                asset -> asset.startMaintenance(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset returnFromMaintenance(DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context) {
        return mutate(id, expectedVersion, custodian, context, "maintenance-return", AssetCustodyEventType.RETURNED,
                "itam.asset.returned.v1", false,
                asset -> asset.returnFromMaintenance(custodian, context.actorId(), context.reason(), clock.instant()));
    }

    public Asset retire(DomainIdentifier id, long expectedVersion, AssetCommandContext context) {
        requireEnabled();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        String fingerprint = fingerprint("retire", id, expectedVersion, context.reason(), context.evidenceReference());
        return execute(transaction -> {
            Optional<AssetIdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) return replay(prior.orElseThrow(), fingerprint, "retire");
            Asset current = requireAsset(id);
            if (current.version() != expectedVersion) {
                throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
            }
            Asset changed = current.retire(context.actorId(), context.reason(), clock.instant());
            AssetCustodyEvent custody = custody(current, changed, AssetCustodyEventType.RETIRED, context);
            assets.update(changed, expectedVersion, custody);
            transaction.append(event("itam.asset.retired.v1", changed, context));
            idempotency.insert(new AssetIdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "retire", changed.id(), clock.instant()));
            return changed;
        });
    }

    public Asset dispose(DomainIdentifier id, long expectedVersion, AssetCommandContext context) {
        if (context.evidenceReference() == null) {
            throw new IllegalArgumentException("disposal requires evidenceReference");
        }
        return mutate(id, expectedVersion, AssetCustodian.none(), context, "dispose", AssetCustodyEventType.DISPOSED,
                "itam.asset.disposed.v1", false, asset -> asset.dispose(context.actorId(), context.reason(), clock.instant()));
    }

    public Asset get(DomainIdentifier id) { requireEnabled(); return requireAsset(id); }

    public AssetPage search(AssetSearchCriteria criteria) {
        requireEnabled(); return assets.search(Objects.requireNonNull(criteria, "criteria"));
    }

    public List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit) {
        requireEnabled(); requireAsset(assetId);
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence cannot be negative");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return assets.custodyHistory(assetId, afterSequence, limit);
    }

    private Asset mutate(
            DomainIdentifier id, long expectedVersion, AssetCustodian custodian, AssetCommandContext context,
            String operation, AssetCustodyEventType custodyType, String eventType, boolean readinessRequired,
            Transition transition) {
        requireEnabled(); Objects.requireNonNull(id, "id"); Objects.requireNonNull(custodian, "custodian");
        Objects.requireNonNull(context, "context"); if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        String fingerprint = fingerprint(operation, id, expectedVersion, custodian, context.reason(), context.evidenceReference());
        return execute(transaction -> {
            Optional<AssetIdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) return replay(prior.orElseThrow(), fingerprint, operation);
            Asset current = requireAsset(id);
            if (current.version() != expectedVersion) throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
            references.validateCustodian(custodian, current.owningOrganizationId(), LocalDate.now(clock),
                    custodyType == AssetCustodyEventType.MAINTENANCE_STARTED);
            Asset changed = transition.apply(current);
            if (readinessRequired || changed.lifecycleStatus().operationalReadinessRequired()) {
                readiness.requireReady(changed, changed.lifecycleStatus());
            }
            AssetCustodyEvent custody = custody(current, changed, custodyType, context);
            assets.update(changed, expectedVersion, custody);
            transaction.append(event(eventType, changed, context));
            idempotency.insert(new AssetIdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, operation, changed.id(), clock.instant()));
            return changed;
        });
    }

    private Asset replay(AssetIdempotencyRepository.Record prior, String fingerprint, String operation) {
        if (!prior.operation().equals(operation) || !prior.payloadSha256().equals(fingerprint)) {
            throw new AssetConflictException("IDEMPOTENCY_CONFLICT", "idempotency key was used with another payload");
        }
        return assets.findById(prior.assetId()).orElseThrow(AssetNotFoundException::new);
    }

    private Asset requireAsset(DomainIdentifier id) {
        return assets.findById(Objects.requireNonNull(id, "id")).orElseThrow(AssetNotFoundException::new);
    }

    private AssetCustodyEvent custody(
            Asset before, Asset after, AssetCustodyEventType eventType, AssetCommandContext context) {
        return new AssetCustodyEvent(ids.next(), after.id(), after.version(), eventType,
                before == null ? null : before.lifecycleStatus(), after.lifecycleStatus(), after.custodian(),
                after.updatedAt(), context.actorId(), context.correlationId(), context.reason(), context.evidenceReference());
    }

    private EventEnvelope event(String type, Asset asset, AssetCommandContext context) {
        String payload = "{" +
                "\"asset_id\":\"" + asset.id() + "\"," +
                "\"rsot_object_id\":\"" + asset.rsotObjectId() + "\"," +
                "\"organization_id\":\"" + asset.owningOrganizationId() + "\"," +
                "\"lifecycle_status\":\"" + asset.lifecycleStatus().wireValue() + "\"," +
                "\"version\":" + asset.version() + "}";
        return new EventEnvelope(ids.next(), new EventType(type), EVENT_VERSION, clock.instant(), SOURCE,
                context.correlationId(), asset.id(), payload);
    }

    private <T> T execute(io.infranexum.core.events.TransactionalWork<T> work) {
        try { return events.execute(work).value(); }
        catch (TransactionExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AssetConflictException conflict) throw conflict;
            if (cause instanceof AssetNotFoundException notFound) throw notFound;
            if (cause instanceof AssetQuotaException quota) throw quota;
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            throw failure;
        }
    }

    private void requireEnabled() {
        if (!features.assetLifecycleEnabled()) {
            throw new AssetConflictException("ITAM_ASSET_CAPABILITY_UNAVAILABLE", "ITAM asset lifecycle is unavailable");
        }
    }

    private static String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "<null>" : value.toString();
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @FunctionalInterface private interface Transition { Asset apply(Asset asset); }
}
