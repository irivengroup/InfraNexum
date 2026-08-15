package io.infranexum.itam.asset;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.asset.application.AssetCommandContext;
import io.infranexum.itam.asset.application.AssetPage;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.application.CreateAssetCommand;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.ports.AssetFeaturePolicy;
import io.infranexum.itam.asset.ports.AssetIdempotencyRepository;
import io.infranexum.itam.asset.ports.AssetOperationalReadinessPolicy;
import io.infranexum.itam.asset.ports.AssetReferencePolicy;
import io.infranexum.itam.asset.ports.AssetRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dependency-free lifecycle smoke for PGM-07-E02 canonical ITAM assets. */
public final class ItamAssetSmoke {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private ItamAssetSmoke() {}

    public static void main(String[] args) {
        Repository repository = new Repository();
        Idempotency idempotency = new Idempotency();
        InMemoryEventStore events = new InMemoryEventStore();
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {7, 8, 0, 2}));
        DomainIdentifier organization = ids.next();
        DomainIdentifier subdivision = ids.next();
        DomainIdentifier actor = ids.next();
        DomainIdentifier supplier = ids.next();
        DomainIdentifier maintenancePartner = ids.next();
        DomainIdentifier rsot = ids.next();
        DomainIdentifier correlation = ids.next();
        References references = new References(organization, subdivision, supplier, maintenancePartner);

        AssetApplicationService service = service(repository, idempotency, events, ids, references, new Readiness(true), true, 2);
        CreateAssetCommand command = command(rsot, organization, subdivision, supplier);
        AssetCommandContext createContext = context(actor, correlation, "asset-acquire-0001", "Acquisition accepted by ITAM", null);
        Asset acquired = service.create(command, createContext);
        require(acquired.lifecycleStatus() == AssetLifecycleStatus.ACQUIRED, "asset must start acquired");
        require(acquired.version() == 1, "asset version must start at one");
        require(acquired.assetType() == AssetType.HARDWARE, "asset type was not normalized");

        Asset replay = service.create(command, createContext);
        require(replay.id().equals(acquired.id()), "idempotent acquire did not replay the aggregate");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.create(
                command(ids.next(), organization, subdivision, supplier), createContext));
        expectCode("ITAM_ASSET_RSOT_CONFLICT", () -> service.create(
                command(rsot, organization, subdivision, supplier),
                context(actor, correlation, "asset-acquire-0002", "Duplicate canonical link validation", null)));

        Asset received = service.receive(acquired.id(), 1, AssetCustodian.organization(organization),
                context(actor, correlation, "asset-receive-0001", "Goods received and reconciled", "receipt:2026-0001"));
        require(received.lifecycleStatus() == AssetLifecycleStatus.RECEIVED && received.version() == 2,
                "receipt transition failed");
        expectCode("VERSION_CONFLICT", () -> service.transfer(received.id(), 1, AssetCustodian.organization(organization),
                context(actor, correlation, "asset-stale-0001", "Stale version rejection", null)));

        AssetApplicationService failClosed = service(
                repository, idempotency, events, ids, references, new Readiness(false), true, 2);
        expectCode("ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE", () -> failClosed.stock(
                received.id(), 2, AssetCustodian.organization(organization),
                context(actor, correlation, "asset-stock-denied", "Operational readiness must be proven", null)));
        require(repository.findById(received.id()).orElseThrow().version() == 2,
                "readiness denial must not mutate asset state");

        Asset stocked = service.stock(received.id(), 2, AssetCustodian.organization(organization),
                context(actor, correlation, "asset-stock-0001", "Warranty evidence validated for stock", "warranty:W-0001"));
        AssetCustodian actorCustodian = new AssetCustodian(AssetCustodianKind.ACTOR, actor);
        Asset assigned = service.assign(stocked.id(), 3, actorCustodian,
                context(actor, correlation, "asset-assign-0001", "Assigned to accountable user", "assignment:A-0001"));
        Asset deployed = service.deploy(assigned.id(), 4, actorCustodian,
                context(actor, correlation, "asset-deploy-0001", "Deployment accepted", "deployment:D-0001"));
        Asset transferred = service.transfer(deployed.id(), 5,
                new AssetCustodian(AssetCustodianKind.SUBDIVISION, subdivision),
                context(actor, correlation, "asset-transfer-0001", "Transferred to governed subdivision", "transfer:T-0001"));
        Asset maintenance = service.startMaintenance(transferred.id(), 6,
                new AssetCustodian(AssetCustodianKind.PARTNER, maintenancePartner),
                context(actor, correlation, "asset-maint-0001", "Repair sent to approved support partner", "rma:RMA-0001"));
        Asset returned = service.returnFromMaintenance(maintenance.id(), 7, AssetCustodian.organization(organization),
                context(actor, correlation, "asset-return-0001", "Repair accepted after inspection", "rma:RMA-0001"));
        Asset retired = service.retire(returned.id(), 8,
                context(actor, correlation, "asset-retire-0001", "Asset reached governed retirement", "retirement:R-0001"));
        expect(IllegalArgumentException.class, () -> service.dispose(retired.id(), 9,
                context(actor, correlation, "asset-dispose-no-evidence", "Disposition attempted without proof", null)));
        Asset disposed = service.dispose(retired.id(), 9,
                context(actor, correlation, "asset-dispose-0001", "Certified recycling completed", "certificate:CERT-0001"));
        require(disposed.lifecycleStatus() == AssetLifecycleStatus.DISPOSED && disposed.version() == 10,
                "disposition transition failed");
        require(disposed.custodian().kind() == AssetCustodianKind.NONE, "disposed asset must have no active custodian");

        List<AssetCustodyEvent> history = service.custodyHistory(disposed.id(), 0, 100);
        require(history.size() == 10, "custody chain does not contain every mutation");
        for (int index = 0; index < history.size(); index++) {
            require(history.get(index).sequence() == index + 1L, "custody sequence is not contiguous");
        }
        require("certificate:CERT-0001".equals(history.get(history.size() - 1).evidenceReference()),
                "disposition evidence is not retained");
        AssetPage page = service.search(new AssetSearchCriteria(
                organization, AssetType.HARDWARE, AssetLifecycleStatus.DISPOSED, rsot, null, 50));
        require(page.items().size() == 1 && page.items().get(0).id().equals(disposed.id()),
                "portfolio filters returned an incorrect asset");
        require(events.outboxSnapshot().size() == 10, "transactional outbox does not mirror lifecycle mutations");
        require(events.outboxSnapshot().stream().anyMatch(record ->
                        "itam.asset.disposed.v1".equals(record.event().eventType().value())),
                "disposition event missing");

        service.create(command(ids.next(), organization, subdivision, supplier),
                context(actor, correlation, "asset-acquire-0003", "Second governed acquisition", null));
        expect(AssetQuotaException.class, () -> service.create(command(ids.next(), organization, subdivision, supplier),
                context(actor, correlation, "asset-acquire-0004", "Quota boundary verification", null)));

        AssetApplicationService disabled = service(
                repository, idempotency, events, ids, references, new Readiness(true), false, 2);
        expectCode("ITAM_ASSET_CAPABILITY_UNAVAILABLE", () -> disabled.get(disposed.id()));
        expect(IllegalArgumentException.class, () -> new AssetSearchCriteria(null, null, null, null, null, 201));

        System.out.println("java-itam-asset-smoke: PASS");
    }

    private static AssetApplicationService service(
            Repository repository, Idempotency idempotency, InMemoryEventStore events, UuidV7Generator ids,
            AssetReferencePolicy references, AssetOperationalReadinessPolicy readiness, boolean enabled, long limit) {
        return new AssetApplicationService(repository, idempotency, new Features(enabled, limit), references,
                readiness, events, ids, CLOCK);
    }

    private static CreateAssetCommand command(
            DomainIdentifier rsot, DomainIdentifier organization, DomainIdentifier subdivision, DomainIdentifier supplier) {
        return new CreateAssetCommand(rsot, "hardware", organization, subdivision, LocalDate.of(2026, 8, 1),
                new BigDecimal("2500.0000"), "eur", supplier);
    }

    private static AssetCommandContext context(
            DomainIdentifier actor, DomainIdentifier correlation, String key, String reason, String evidence) {
        return new AssetCommandContext(actor, correlation, key, reason, evidence);
    }

    private record Features(boolean assetLifecycleEnabled, long assetLimit) implements AssetFeaturePolicy {}

    private record Readiness(boolean available) implements AssetOperationalReadinessPolicy {
        @Override public void requireReady(Asset asset, AssetLifecycleStatus targetStatus) {
            if (!available && targetStatus.operationalReadinessRequired()) {
                throw new AssetConflictException(
                        "ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE",
                        "warranty/license readiness provider is unavailable");
            }
        }
    }

    private record References(
            DomainIdentifier organization, DomainIdentifier subdivision,
            DomainIdentifier supplier, DomainIdentifier maintenancePartner) implements AssetReferencePolicy {
        @Override public void validateCanonicalObject(DomainIdentifier rsotObjectId, DomainIdentifier organizationId) {
            if (!organization.equals(organizationId)) throw new IllegalArgumentException("unknown organization");
        }
        @Override public void validateSubdivision(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
            if (!organization.equals(organizationId) || !subdivision.equals(subdivisionId)) {
                throw new IllegalArgumentException("unknown subdivision");
            }
        }
        @Override public void validateAcquisitionPartner(
                DomainIdentifier partnerId, DomainIdentifier organizationId, LocalDate effectiveOn) {
            if (!organization.equals(organizationId) || !supplier.equals(partnerId)) {
                throw new IllegalArgumentException("invalid acquisition partner");
            }
        }
        @Override public void validateCustodian(
                AssetCustodian custodian, DomainIdentifier organizationId, LocalDate effectiveOn, boolean maintenance) {
            if (!organization.equals(organizationId)) throw new IllegalArgumentException("unknown organization");
            if (custodian.kind() == AssetCustodianKind.PARTNER && !maintenancePartner.equals(custodian.referenceId())) {
                throw new IllegalArgumentException("invalid maintenance partner");
            }
            if (custodian.kind() == AssetCustodianKind.SUBDIVISION && !subdivision.equals(custodian.referenceId())) {
                throw new IllegalArgumentException("invalid subdivision custodian");
            }
        }
    }

    private static final class Idempotency implements AssetIdempotencyRepository {
        private final Map<String, Record> records = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(records.get(key)); }
        @Override public void insert(Record record) {
            if (records.putIfAbsent(record.key(), record) != null) throw new IllegalStateException("duplicate idempotency key");
        }
    }

    private static final class Repository implements AssetRepository {
        private final Map<DomainIdentifier, Asset> values = new LinkedHashMap<>();
        private final Map<DomainIdentifier, List<AssetCustodyEvent>> custody = new LinkedHashMap<>();

        @Override public long count() { return values.size(); }
        @Override public boolean existsByRsotObjectId(DomainIdentifier rsotObjectId) {
            return values.values().stream().anyMatch(asset -> asset.rsotObjectId().equals(rsotObjectId));
        }
        @Override public Optional<Asset> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public void insert(Asset asset, AssetCustodyEvent acquisitionEvent) {
            if (values.putIfAbsent(asset.id(), asset) != null) throw new IllegalStateException("duplicate asset");
            custody.put(asset.id(), new ArrayList<>(List.of(acquisitionEvent)));
        }
        @Override public void update(Asset asset, long expectedVersion, AssetCustodyEvent custodyEvent) {
            Asset current = values.get(asset.id());
            if (current == null || current.version() != expectedVersion) {
                throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
            }
            values.put(asset.id(), asset);
            custody.computeIfAbsent(asset.id(), ignored -> new ArrayList<>()).add(custodyEvent);
        }
        @Override public AssetPage search(AssetSearchCriteria criteria) {
            List<Asset> filtered = values.values().stream()
                    .filter(asset -> criteria.owningOrganizationId() == null || asset.owningOrganizationId().equals(criteria.owningOrganizationId()))
                    .filter(asset -> criteria.assetType() == null || asset.assetType() == criteria.assetType())
                    .filter(asset -> criteria.lifecycleStatus() == null || asset.lifecycleStatus() == criteria.lifecycleStatus())
                    .filter(asset -> criteria.rsotObjectId() == null || asset.rsotObjectId().equals(criteria.rsotObjectId()))
                    .filter(asset -> criteria.afterId() == null || asset.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(Asset::id))
                    .limit((long) criteria.limit() + 1L)
                    .toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            List<Asset> page = filtered.size() > criteria.limit()
                    ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered;
            return new AssetPage(page, next);
        }
        @Override public List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit) {
            return custody.getOrDefault(assetId, List.of()).stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(AssetCustodyEvent::sequence))
                    .limit(limit)
                    .toList();
        }
    }

    private static void expectCode(String code, ThrowingAction action) {
        try {
            action.run();
        } catch (AssetConflictException error) {
            require(code.equals(error.code()), "unexpected asset code: " + error.code());
            return;
        } catch (Exception error) {
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected AssetConflictException " + code);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            require(type.isInstance(error), "unexpected exception type: " + error);
            return;
        }
        throw new AssertionError("expected exception " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
