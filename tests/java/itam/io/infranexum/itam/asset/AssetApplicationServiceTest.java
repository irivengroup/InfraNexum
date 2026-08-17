package io.infranexum.itam.asset;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.core.events.OutboxStatus;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionOutcome;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
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
import io.infranexum.itam.asset.domain.AssetNotFoundException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Application-layer regressions for atomic PGM-07-E02 lifecycle, idempotency and readiness. */
final class AssetApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DomainIdentifier ORG = id(1);
    private static final DomainIdentifier SUB = id(2);
    private static final DomainIdentifier ACTOR = id(3);
    private static final DomainIdentifier CORR = id(4);
    private static final DomainIdentifier SUPPLIER = id(5);
    private static final DomainIdentifier MAINTAINER = id(6);
    private static final DomainIdentifier PRODUCER = id(7);

    private Repository repository;
    private Idempotency idempotency;
    private MutableFeatures features;
    private References references;
    private MutableReadiness readiness;
    private InMemoryEventStore events;
    private AssetApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new Repository();
        idempotency = new Idempotency();
        features = new MutableFeatures(true, 10);
        references = new References();
        readiness = new MutableReadiness(true);
        events = new InMemoryEventStore();
        service = new AssetApplicationService(repository, idempotency, features, references, readiness, events, ids(), CLOCK);
    }

    @Test
    void createValidatesReferencesQuotaUniquenessIdempotencyAndMinimalEvent() {
        DomainIdentifier rsot = id(10);
        Asset created = service.create(command(rsot), context("acquire-0001", "Initial governed acquisition", null));
        assertEquals(AssetLifecycleStatus.ACQUIRED, created.lifecycleStatus());
        assertEquals(1, references.canonicalChecks);
        assertEquals(1, references.subdivisionChecks);
        assertEquals(1, references.acquisitionPartnerChecks);
        assertEquals(created.id(), service.create(command(rsot), context("acquire-0001", "Initial governed acquisition", null)).id());
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.create(command(id(11)), context("acquire-0001", "Initial governed acquisition", null)));
        assertCode("ITAM_ASSET_RSOT_CONFLICT", () -> service.create(command(rsot), context("acquire-0002", "Duplicate RSOT link", null)));
        String payload = events.outboxSnapshot().get(0).event().payload();
        assertTrue(payload.contains("\"asset_id\""));
        assertFalse(payload.contains("2500"));
        assertFalse(payload.contains("EUR"));

        features.limit = 1;
        assertThrows(AssetQuotaException.class, () -> service.create(command(id(12)), context("acquire-0003", "Quota validation", null)));
        features.enabled = false;
        assertCode("ITAM_ASSET_CAPABILITY_UNAVAILABLE", () -> service.get(created.id()));
    }


    @Test
    void optionalAcquisitionReferencesAndMutationReplayBoundariesRemainDeterministic() {
        CreateAssetCommand minimal = new CreateAssetCommand(
                id(13), "hardware", ORG, null, LocalDate.of(2026, 8, 1), BigDecimal.ONE, "EUR", null, PRODUCER);
        Asset created = service.create(minimal, context("acquire-minimal-0013", "Minimal governed acquisition", null));
        assertNull(created.owningSubdivisionId());
        assertNull(created.acquiredFromPartnerId());
        assertEquals(PRODUCER, created.producerPartnerId());
        assertEquals(0, references.subdivisionChecks);
        assertEquals(0, references.acquisitionPartnerChecks);
        assertEquals(1, references.producerChecks);

        assertThrows(IllegalArgumentException.class, () -> service.setProducer(created.id(), 0, PRODUCER,
                context("producer-invalid-version", "Reject invalid producer version", null)));
        Asset unchanged = service.setProducer(created.id(), created.version(), PRODUCER,
                context("producer-noop", "Producer already canonical", null));
        assertSame(created, unchanged);

        Asset received = service.receive(created.id(), created.version(), AssetCustodian.organization(ORG),
                context("receive-replay", "Receive asset", "receipt:13"));
        assertEquals(received.id(), service.receive(created.id(), created.version(), AssetCustodian.organization(ORG),
                context("receive-replay", "Receive asset", "receipt:13")).id());
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.receive(created.id(), created.version(), AssetCustodian.organization(ORG),
                context("receive-replay", "Different receipt payload", "receipt:other")));
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.transfer(received.id(), received.version(), AssetCustodian.organization(ORG),
                context("receive-replay", "Receive asset", "receipt:13")));

        assertThrows(IllegalArgumentException.class, () -> service.retire(received.id(), 0,
                context("retire-invalid-version", "Reject invalid version", "evidence:retire")));
        assertCode("VERSION_CONFLICT", () -> service.retire(received.id(), received.version() - 1,
                context("retire-stale-version", "Reject stale version", "evidence:retire")));
        Asset retired = service.retire(received.id(), received.version(),
                context("retire-replay", "Retire asset", "evidence:retire"));
        assertEquals(retired.id(), service.retire(received.id(), received.version(),
                context("retire-replay", "Retire asset", "evidence:retire")).id());
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.retire(received.id(), received.version(),
                context("retire-replay", "Different retirement payload", "evidence:different")));
    }

    @Test
    void producerCorrectionIsVersionedIdempotentAndValidatedWithoutBreakingLegacyAcquisition() {
        Asset legacy = service.create(command(id(18)), context("acquire-0018", "Legacy acquisition without producer", null));
        assertNull(legacy.producerPartnerId());
        Asset corrected = service.setProducer(legacy.id(), 1, PRODUCER,
                context("producer-0018", "Canonical manufacturer correction", "evidence:producer:18"));
        assertEquals(PRODUCER, corrected.producerPartnerId());
        assertEquals(2, corrected.version());
        assertEquals(1, references.producerChecks);
        assertEquals(corrected.id(), service.setProducer(legacy.id(), 1, PRODUCER,
                context("producer-0018", "Canonical manufacturer correction", "evidence:producer:18")).id());
        assertCode("VERSION_CONFLICT", () -> service.setProducer(corrected.id(), 1, PRODUCER,
                context("producer-stale-0018", "Stale producer correction", null)));
        assertTrue(events.outboxSnapshot().stream().anyMatch(record ->
                "itam.asset.producer_updated.v1".equals(record.event().eventType().value())));
    }

    @Test
    void createRejectsFutureDatesAndPropagatesReferencePolicyFailures() {
        CreateAssetCommand future = new CreateAssetCommand(
                id(20), "hardware", ORG, SUB, LocalDate.of(2027, 1, 1), BigDecimal.ONE, "EUR", SUPPLIER);
        assertThrows(IllegalArgumentException.class, () -> service.create(future, context("future-0001", "Future date validation", null)));
        references.failure = new AssetConflictException("REFERENCE_FAILURE", "invalid reference");
        assertCode("REFERENCE_FAILURE", () -> service.create(command(id(21)), context("reference-0001", "Reference validation", null)));
    }

    @Test
    void lifecycleEnforcesVersionReadinessCustodyAndDisposalEvidence() {
        Asset acquired = service.create(command(id(30)), context("acquire-0030", "Initial governed acquisition", null));
        Asset received = service.receive(acquired.id(), 1, AssetCustodian.organization(ORG),
                context("receive-0030", "Receipt accepted", "receipt:30"));
        assertEquals(2, received.version());
        assertCode("VERSION_CONFLICT", () -> service.transfer(received.id(), 1, AssetCustodian.organization(ORG),
                context("stale-0030", "Stale version validation", null)));

        readiness.ready = false;
        assertCode("ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE", () -> service.stock(received.id(), 2, AssetCustodian.organization(ORG),
                context("stock-denied-0030", "Readiness unavailable", null)));
        assertEquals(2, repository.findById(received.id()).orElseThrow().version());
        readiness.ready = true;
        Asset stocked = service.stock(received.id(), 2, AssetCustodian.organization(ORG),
                context("stock-0030", "Compliance verified", "warranty:30"));
        Asset assigned = service.assign(stocked.id(), 3, new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR),
                context("assign-0030", "Assigned internally", "assignment:30"));
        Asset deployed = service.deploy(assigned.id(), 4, new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR),
                context("deploy-0030", "Deployment verified", "deployment:30"));
        Asset transferred = service.transfer(deployed.id(), 5, new AssetCustodian(AssetCustodianKind.SUBDIVISION, SUB),
                context("transfer-0030", "Custody transferred", "transfer:30"));
        Asset maintenance = service.startMaintenance(transferred.id(), 6, new AssetCustodian(AssetCustodianKind.PARTNER, MAINTAINER),
                context("maint-0030", "Maintenance started", "rma:30"));
        assertTrue(references.lastMaintenance);
        Asset returned = service.returnFromMaintenance(maintenance.id(), 7, AssetCustodian.organization(ORG),
                context("return-0030", "Maintenance completed", "rma:30"));
        Asset retired = service.retire(returned.id(), 8, context("retire-0030", "Retirement approved", "retire:30"));
        assertThrows(IllegalArgumentException.class, () -> service.dispose(retired.id(), 9,
                context("dispose-no-proof", "Disposition without proof", null)));
        Asset disposed = service.dispose(retired.id(), 9,
                context("dispose-0030", "Certified recycling", "certificate:30"));
        assertEquals(AssetLifecycleStatus.DISPOSED, disposed.lifecycleStatus());
        assertEquals(10, service.custodyHistory(disposed.id(), 0, 100).size());
        assertEquals("certificate:30", service.custodyHistory(disposed.id(), 9, 1).get(0).evidenceReference());
        assertEquals(10, events.outboxSnapshot().size());
    }

    @Test
    void readsAreBoundedAndSearchDelegatesFilters() {
        Asset created = service.create(command(id(40)), context("acquire-0040", "Initial governed acquisition", null));
        AssetSearchCriteria criteria = new AssetSearchCriteria(ORG, AssetType.HARDWARE, AssetLifecycleStatus.ACQUIRED, created.rsotObjectId(), null, 20);
        AssetPage page = service.search(criteria);
        assertEquals(1, page.items().size());
        assertSame(criteria, repository.lastSearch);
        assertEquals(created.id(), service.get(created.id()).id());
        assertThrows(AssetNotFoundException.class, () -> service.get(id(999)));
        assertThrows(AssetNotFoundException.class, () -> service.custodyHistory(id(999), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.custodyHistory(created.id(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.custodyHistory(created.id(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.custodyHistory(created.id(), 0, 201));
        assertThrows(NullPointerException.class, () -> service.search(null));
    }

    @Test
    void constructorAndMutationArgumentsRejectInvalidInputs() {
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(null, idempotency, features, references, readiness, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, null, features, references, readiness, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, null, references, readiness, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, features, null, readiness, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, features, references, null, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, features, references, readiness, null, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, features, references, readiness, events, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new AssetApplicationService(repository, idempotency, features, references, readiness, events, ids(), null));
        assertThrows(NullPointerException.class, () -> service.create(null, context("null-0001", "Null command validation", null)));
        assertThrows(NullPointerException.class, () -> service.create(command(id(50)), null));
        Asset created = service.create(command(id(51)), context("acquire-0051", "Initial governed acquisition", null));
        assertThrows(NullPointerException.class, () -> service.receive(null, 1, AssetCustodian.organization(ORG), context("null-0002", "Null id validation", null)));
        assertThrows(NullPointerException.class, () -> service.receive(created.id(), 1, null, context("null-0003", "Null custodian validation", null)));
        assertThrows(IllegalArgumentException.class, () -> service.receive(created.id(), 0, AssetCustodian.organization(ORG), context("badver-01", "Bad version validation", null)));
    }

    @Test
    void transactionWrapperPreservesKnownBusinessCausesAndUnknownInfrastructureFailure() {
        TransactionalEventStore wrappedConflict = failingStore(new AssetConflictException("WRAPPED_CONFLICT", "wrapped"));
        AssetApplicationService conflictService = new AssetApplicationService(
                repository, idempotency, features, references, readiness, wrappedConflict, ids(), CLOCK);
        assertCode("WRAPPED_CONFLICT", () -> conflictService.create(command(id(60)), context("wrapped-01", "Wrapped conflict validation", null)));

        TransactionalEventStore wrappedNotFound = failingStore(new AssetNotFoundException());
        AssetApplicationService notFoundService = new AssetApplicationService(
                repository, idempotency, features, references, readiness, wrappedNotFound, ids(), CLOCK);
        assertThrows(AssetNotFoundException.class, () -> notFoundService.create(command(id(63)), context("wrapped-04", "Wrapped not found", null)));

        TransactionalEventStore wrappedQuota = failingStore(new AssetQuotaException());
        AssetApplicationService quotaService = new AssetApplicationService(
                repository, idempotency, features, references, readiness, wrappedQuota, ids(), CLOCK);
        assertThrows(AssetQuotaException.class, () -> quotaService.create(command(id(64)), context("wrapped-05", "Wrapped quota", null)));

        TransactionalEventStore wrappedInvalid = failingStore(new IllegalArgumentException("invalid"));
        AssetApplicationService invalidService = new AssetApplicationService(
                repository, idempotency, features, references, readiness, wrappedInvalid, ids(), CLOCK);
        assertThrows(IllegalArgumentException.class, () -> invalidService.create(command(id(61)), context("wrapped-02", "Wrapped invalid validation", null)));

        TransactionalEventStore wrappedUnknown = failingStore(new IllegalStateException("database unavailable"));
        AssetApplicationService unknownService = new AssetApplicationService(
                repository, idempotency, features, references, readiness, wrappedUnknown, ids(), CLOCK);
        assertThrows(TransactionExecutionException.class, () -> unknownService.create(command(id(62)), context("wrapped-03", "Wrapped unknown validation", null)));
    }

    private static TransactionalEventStore failingStore(Throwable cause) {
        return new TransactionalEventStore() {
            @Override public <T> TransactionOutcome<T> execute(TransactionalWork<T> work) {
                throw new TransactionExecutionException("wrapped", cause);
            }
            @Override public List<io.infranexum.core.events.OutboxRecord> claimBatch(String workerId, int limit, Instant now, Duration lease) { return List.of(); }
            @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt) {}
            @Override public OutboxStatus markFailed(DomainIdentifier eventId, String workerId, Instant failedAt, RetryPolicy retryPolicy, Throwable failure) { return OutboxStatus.DEAD_LETTER; }
        };
    }

    private static CreateAssetCommand command(DomainIdentifier rsot) {
        return new CreateAssetCommand(rsot, "hardware", ORG, SUB, LocalDate.of(2026, 8, 1), new BigDecimal("2500.00"), "EUR", SUPPLIER);
    }

    private static AssetCommandContext context(String key, String reason, String evidence) {
        return new AssetCommandContext(ACTOR, CORR, key, reason, evidence);
    }

    private static UuidV7Generator ids() {
        return new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {7, 8, 0, 2}));
    }

    private static DomainIdentifier id(int suffix) {
        return DomainIdentifier.parse("01900000-0000-7000-8000-" + String.format("%012d", suffix));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        AssetConflictException failure = assertThrows(AssetConflictException.class, executable);
        assertEquals(code, failure.code());
    }

    private static final class MutableFeatures implements AssetFeaturePolicy {
        boolean enabled;
        long limit;
        MutableFeatures(boolean enabled, long limit) { this.enabled = enabled; this.limit = limit; }
        @Override public boolean assetLifecycleEnabled() { return enabled; }
        @Override public long assetLimit() { return limit; }
    }

    private static final class MutableReadiness implements AssetOperationalReadinessPolicy {
        boolean ready;
        MutableReadiness(boolean ready) { this.ready = ready; }
        @Override public void requireReady(Asset asset, AssetLifecycleStatus targetStatus) {
            if (!ready) throw new AssetConflictException("ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE", "compliance evidence unavailable");
        }
    }

    private static final class References implements AssetReferencePolicy {
        int canonicalChecks;
        int subdivisionChecks;
        int acquisitionPartnerChecks;
        int producerChecks;
        boolean lastMaintenance;
        RuntimeException failure;
        @Override public void validateCanonicalObject(DomainIdentifier rsotObjectId, DomainIdentifier organizationId) {
            canonicalChecks++;
            failIfRequested();
        }
        @Override public void validateSubdivision(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
            subdivisionChecks++;
            failIfRequested();
        }
        @Override public void validateAcquisitionPartner(DomainIdentifier partnerId, DomainIdentifier organizationId, LocalDate effectiveOn) {
            acquisitionPartnerChecks++;
            failIfRequested();
        }
        @Override public void validateProducerPartner(DomainIdentifier partnerId, DomainIdentifier organizationId, AssetType assetType, LocalDate effectiveOn) {
            producerChecks++;
            failIfRequested();
        }
        @Override public void validateCustodian(AssetCustodian custodian, DomainIdentifier organizationId, LocalDate effectiveOn, boolean maintenance) {
            lastMaintenance = maintenance;
            failIfRequested();
        }
        private void failIfRequested() { if (failure != null) throw failure; }
    }

    private static final class Idempotency implements AssetIdempotencyRepository {
        private final Map<String, Record> values = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void insert(Record record) { values.put(record.key(), record); }
    }

    private static final class Repository implements AssetRepository {
        private final Map<DomainIdentifier, Asset> values = new LinkedHashMap<>();
        private final Map<DomainIdentifier, List<AssetCustodyEvent>> custody = new LinkedHashMap<>();
        AssetSearchCriteria lastSearch;
        @Override public long count() { return values.size(); }
        @Override public boolean existsByRsotObjectId(DomainIdentifier rsotObjectId) {
            return values.values().stream().anyMatch(value -> value.rsotObjectId().equals(rsotObjectId));
        }
        @Override public Optional<Asset> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public void insert(Asset asset, AssetCustodyEvent acquisitionEvent) {
            values.put(asset.id(), asset);
            custody.put(asset.id(), new ArrayList<>(List.of(acquisitionEvent)));
        }
        @Override public void update(Asset asset, long expectedVersion, AssetCustodyEvent custodyEvent) {
            Asset current = values.get(asset.id());
            if (current == null || current.version() != expectedVersion) throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
            values.put(asset.id(), asset);
            custody.computeIfAbsent(asset.id(), ignored -> new ArrayList<>()).add(custodyEvent);
        }
        @Override public void updateMetadata(Asset asset, long expectedVersion) {
            Asset current = values.get(asset.id());
            if (current == null || current.version() != expectedVersion) throw new AssetConflictException("VERSION_CONFLICT", "asset version changed");
            values.put(asset.id(), asset);
        }
        @Override public AssetPage search(AssetSearchCriteria criteria) {
            lastSearch = criteria;
            List<Asset> filtered = values.values().stream()
                    .filter(value -> criteria.owningOrganizationId() == null || value.owningOrganizationId().equals(criteria.owningOrganizationId()))
                    .filter(value -> criteria.assetType() == null || value.assetType() == criteria.assetType())
                    .filter(value -> criteria.lifecycleStatus() == null || value.lifecycleStatus() == criteria.lifecycleStatus())
                    .filter(value -> criteria.rsotObjectId() == null || value.rsotObjectId().equals(criteria.rsotObjectId()))
                    .filter(value -> criteria.afterId() == null || value.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(Asset::id)).limit((long) criteria.limit() + 1).toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            return new AssetPage(filtered.size() > criteria.limit() ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered, next);
        }
        @Override public List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit) {
            return custody.getOrDefault(assetId, List.of()).stream().filter(event -> event.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(AssetCustodyEvent::sequence)).limit(limit).toList();
        }
    }
}
