package io.infranexum.dcim.facility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.dcim.facility.application.CreateFacilityCommand;
import io.infranexum.dcim.facility.application.FacilityApplicationService;
import io.infranexum.dcim.facility.application.FacilityCommandContext;
import io.infranexum.dcim.facility.application.FacilityPage;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.domain.FacilityCode;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityQuotaException;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import io.infranexum.dcim.facility.ports.FacilityFeaturePolicy;
import io.infranexum.dcim.facility.ports.FacilityIdempotencyRepository;
import io.infranexum.dcim.facility.ports.FacilityRepository;
import io.infranexum.dcim.facility.ports.FacilityScopePolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Application-service regressions for hierarchy, idempotency, concurrency, quotas and lifecycle events. */
final class FacilityApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DomainIdentifier ORG = DomainIdentifier.parse("01900000-0000-7000-8000-000000000010");
    private static final DomainIdentifier SUB = DomainIdentifier.parse("01900000-0000-7000-8000-000000000011");
    private static final DomainIdentifier ACTOR = DomainIdentifier.parse("01900000-0000-7000-8000-000000000012");
    private static final DomainIdentifier CORR = DomainIdentifier.parse("01900000-0000-7000-8000-000000000013");

    private Repository repository;
    private Idempotency idempotency;
    private Limits limits;
    private InMemoryEventStore events;
    private FacilityApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new Repository();
        idempotency = new Idempotency();
        limits = new Limits(true, 10);
        events = new InMemoryEventStore();
        service = service(repository, idempotency, limits, events);
    }

    @Test
    void hierarchyCreateIsIdempotentScopedAndSearchable() {
        FacilityCommandContext create = context("dcim-site-create-001", "Register primary site");
        FacilityNode site = service.create(site("PAR01"), create);
        assertEquals(site.id(), service.create(site("PAR01"), create).id());
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.create(site("PAR02"), create));
        assertCode("DCIM_CODE_DUPLICATE", () -> service.create(site("PAR01"), context("dcim-site-create-002", "Duplicate site")));

        site = service.changeStatus(site.id(), 1, FacilityStatus.ACTIVE, context("dcim-site-active-001", "Activate site"));
        FacilityNode building = service.create(building(site.id(), "BLD01"), context("dcim-building-create-001", "Register building"));
        assertEquals(site.id(), building.parentId());
        assertEquals(site.id(), building.scopeId());

        FacilityPage frenchSites = service.search(new FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, FacilityStatus.ACTIVE, "fr", null, 50));
        assertEquals(List.of(site.id()), frenchSites.items().stream().map(FacilityNode::id).toList());
        assertTrue(events.outboxSnapshot().stream().anyMatch(record -> "dcim.site.created.v1".equals(record.event().eventType().value())));
    }

    @Test
    void parentMustBeActiveCorrectKindAndSameGovernanceScope() {
        FacilityNode draftSite = service.create(site("PAR01"), context("dcim-site-create-001", "Register site"));
        assertCode("DCIM_PARENT_INACTIVE", () -> service.create(building(draftSite.id(), "BLD01"), context("dcim-building-create-001", "Inactive parent")));

        FacilityNode site = service.changeStatus(draftSite.id(), 1, FacilityStatus.ACTIVE, context("dcim-site-active-001", "Activate site"));
        FacilityNode building = service.create(building(site.id(), "BLD01"), context("dcim-building-create-002", "Register building"));
        building = service.changeStatus(building.id(), 1, FacilityStatus.ACTIVE, context("dcim-building-active-001", "Activate building"));
        FacilityNode activeBuilding = building;
        assertCode("DCIM_PARENT_KIND_INVALID", () -> service.create(building(activeBuilding.id(), "BLD02"), context("dcim-building-create-003", "Wrong parent type")));

        DomainIdentifier otherSub = DomainIdentifier.parse("01900000-0000-7000-8000-000000000099");
        CreateFacilityCommand wrongScope = new CreateFacilityCommand(FacilityKind.FLOOR, ORG, otherSub, activeBuilding.id(), "F01", "Floor one",
                null, null, null, null, null, null, null, null, null, 1, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, null, null, null);
        assertCode("DCIM_SUBDIVISION_INVALID", () -> service.create(wrongScope, context("dcim-floor-create-001", "Wrong scope")));
    }

    @Test
    void siteArchivalBlocksOnlyActiveBuildingsAndEmitsSpecificEvents() {
        FacilityNode site = service.create(site("PAR01"), context("dcim-site-create-001", "Register site"));
        site = service.changeStatus(site.id(), 1, FacilityStatus.ACTIVE, context("dcim-site-active-001", "Activate site"));
        FacilityNode zone = service.create(zone(site.id(), "ZONE1"), context("dcim-zone-create-001", "Register site zone"));
        zone = service.changeStatus(zone.id(), 1, FacilityStatus.ACTIVE, context("dcim-zone-active-001", "Activate zone"));

        // A directly-attached active technical zone is not a Building and must not over-block the CDC Site rule.
        FacilityNode archived = service.changeStatus(site.id(), 2, FacilityStatus.ARCHIVED, context("dcim-site-archive-001", "Archive without buildings"));
        assertEquals(FacilityStatus.ARCHIVED, archived.status());
        assertTrue(events.outboxSnapshot().stream().anyMatch(record -> "dcim.site.archived.v1".equals(record.event().eventType().value())));

        Repository otherRepo = new Repository();
        InMemoryEventStore otherEvents = new InMemoryEventStore();
        FacilityApplicationService other = service(otherRepo, new Idempotency(), new Limits(true, 10), otherEvents);
        FacilityNode otherSite = other.create(site("LYO01"), context("dcim-site-create-010", "Register Lyon site"));
        otherSite = other.changeStatus(otherSite.id(), 1, FacilityStatus.ACTIVE, context("dcim-site-active-010", "Activate Lyon site"));
        FacilityNode building = other.create(building(otherSite.id(), "BLD10"), context("dcim-building-create-010", "Register building"));
        other.changeStatus(building.id(), 1, FacilityStatus.ACTIVE, context("dcim-building-active-010", "Activate building"));
        FacilityNode protectedSite = otherSite;
        assertCode("DCIM_SITE_ARCHIVE_BLOCKED", () -> other.changeStatus(protectedSite.id(), 2, FacilityStatus.ARCHIVED,
                context("dcim-site-archive-010", "Verify building protection")));
    }

    @Test
    void optimisticConcurrencyQuotaAndCapabilityAreFailClosed() {
        FacilityNode site = service.create(site("PAR01"), context("dcim-site-create-001", "Register site"));
        assertCode("VERSION_CONFLICT", () -> service.changeStatus(site.id(), 2, FacilityStatus.ACTIVE,
                context("dcim-site-active-stale", "Stale version")));

        Limits oneSite = new Limits(true, 1);
        FacilityApplicationService quotaService = service(new Repository(), new Idempotency(), oneSite, new InMemoryEventStore());
        quotaService.create(site("PAR01"), context("dcim-site-create-010", "Register one site"));
        assertThrows(FacilityQuotaException.class, () -> quotaService.create(site("PAR02"), context("dcim-site-create-011", "Overflow quota")));

        FacilityApplicationService disabled = service(repository, idempotency, new Limits(false, 10), events);
        assertCode("DCIM_FACILITY_CAPABILITY_UNAVAILABLE", () -> disabled.get(site.id()));
    }

    @Test
    void roomLockEmitsSpecificEventAndUnknownIdsRemainNotFound() {
        FacilityNode site = active(service.create(site("PAR01"), context("dcim-site-create-001", "Register site")), "site", service);
        FacilityNode building = active(service.create(building(site.id(), "BLD01"), context("dcim-building-create-001", "Register building")), "building", service);
        FacilityNode floor = active(service.create(floor(building.id(), "F01"), context("dcim-floor-create-001", "Register floor")), "floor", service);
        FacilityNode room = active(service.create(room(floor.id(), "ROOM1"), context("dcim-room-create-001", "Register room")), "room", service);
        FacilityNode locked = service.changeStatus(room.id(), 2, FacilityStatus.LOCKED, context("dcim-room-lock-001", "Lock room"));
        assertEquals(FacilityStatus.LOCKED, locked.status());
        assertTrue(events.outboxSnapshot().stream().anyMatch(record -> "dcim.room.locked.v1".equals(record.event().eventType().value())));
        assertThrows(io.infranexum.dcim.facility.domain.FacilityNotFoundException.class,
                () -> service.get(DomainIdentifier.parse("01900000-0000-7000-8000-000000000099")));
    }

    private static FacilityNode active(FacilityNode node, String prefix, FacilityApplicationService app) {
        return app.changeStatus(node.id(), node.version(), FacilityStatus.ACTIVE, context("dcim-" + prefix + "-activate-999", "Activate node"));
    }

    private static FacilityApplicationService service(Repository repository, Idempotency idempotency, Limits limits, InMemoryEventStore events) {
        return new FacilityApplicationService(repository, idempotency, limits, new Scope(), events,
                new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {8, 1, 0, 4})), CLOCK);
    }

    private static CreateFacilityCommand site(String code) {
        return new CreateFacilityCommand(FacilityKind.SITE, ORG, SUB, null, code, "Primary site", "10 Rue de Rivoli", null,
                "75001", "Paris", "FR", "Europe/Paris", new BigDecimal("48.8566000"), new BigDecimal("2.3522000"),
                null, null, null, null, null, null, null, "Primary DCIM site");
    }

    private static CreateFacilityCommand building(DomainIdentifier parent, String code) {
        return new CreateFacilityCommand(FacilityKind.BUILDING, ORG, SUB, parent, code, "Building one", null, null, null, null, null, null,
                new BigDecimal("48.8567000"), new BigDecimal("2.3523000"), 4, null, new BigDecimal("1200"), null, null, null, null, "Building");
    }

    private static CreateFacilityCommand floor(DomainIdentifier parent, String code) {
        return new CreateFacilityCommand(FacilityKind.FLOOR, ORG, SUB, parent, code, "Floor one", null, null, null, null, null, null,
                null, null, null, 1, new BigDecimal("300"), new BigDecimal("3.5"), new BigDecimal("180"), null, null, "Floor");
    }

    private static CreateFacilityCommand room(DomainIdentifier parent, String code) {
        return new CreateFacilityCommand(FacilityKind.ROOM, ORG, SUB, parent, code, "Secure room", null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("80"), null, new BigDecimal("120"), "secure", null, "Room");
    }

    private static CreateFacilityCommand zone(DomainIdentifier parent, String code) {
        return new CreateFacilityCommand(FacilityKind.ZONE, ORG, SUB, parent, code, "Cooling zone", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "cooling", "Zone");
    }

    private static FacilityCommandContext context(String key, String reason) { return new FacilityCommandContext(ACTOR, CORR, key, reason); }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        FacilityConflictException failure = assertThrows(FacilityConflictException.class, executable);
        assertEquals(code, failure.code());
    }

    private static final class Limits implements FacilityFeaturePolicy {
        private final boolean enabled;
        private final long siteLimit;
        private Limits(boolean enabled, long siteLimit) { this.enabled = enabled; this.siteLimit = siteLimit; }
        @Override public boolean facilitiesEnabled() { return enabled; }
        @Override public long limit(FacilityKind kind) { return kind == FacilityKind.SITE ? siteLimit : 100; }
    }

    private static final class Scope implements FacilityScopePolicy {
        @Override public void requireActiveScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
            if (!ORG.equals(organizationId)) throw new FacilityConflictException("DCIM_ORGANIZATION_INVALID", "organization unavailable");
            if (!SUB.equals(subdivisionId)) throw new FacilityConflictException("DCIM_SUBDIVISION_INVALID", "subdivision unavailable");
        }
    }

    private static final class Idempotency implements FacilityIdempotencyRepository {
        private final Map<String, Record> records = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(records.get(key)); }
        @Override public void insert(Record record) { if (records.putIfAbsent(record.key(), record) != null) throw new IllegalStateException("duplicate key"); }
    }

    private static final class Repository implements FacilityRepository {
        private final Map<DomainIdentifier, FacilityNode> values = new LinkedHashMap<>();
        @Override public long count(FacilityKind kind) { return values.values().stream().filter(value -> value.kind() == kind).count(); }
        @Override public boolean existsByScopeCode(FacilityKind kind, DomainIdentifier scopeId, FacilityCode code) {
            return values.values().stream().anyMatch(value -> value.kind() == kind && value.scopeId().equals(scopeId) && value.code().equals(code));
        }
        @Override public Optional<FacilityNode> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public long activeBuildingsForSite(DomainIdentifier siteId) {
            return values.values().stream().filter(value -> siteId.equals(value.parentId()) && value.kind() == FacilityKind.BUILDING && value.status() == FacilityStatus.ACTIVE).count();
        }
        @Override public void insert(FacilityNode node) { values.put(node.id(), node); }
        @Override public void update(FacilityNode node, long expectedVersion) {
            FacilityNode current = values.get(node.id());
            if (current == null || current.version() != expectedVersion) throw new FacilityConflictException("VERSION_CONFLICT", "facility version changed");
            values.put(node.id(), node);
        }
        @Override public FacilityPage search(FacilitySearchCriteria criteria) {
            List<FacilityNode> filtered = values.values().stream()
                    .filter(value -> criteria.organizationId() == null || criteria.organizationId().equals(value.organizationId()))
                    .filter(value -> criteria.subdivisionId() == null || criteria.subdivisionId().equals(value.subdivisionId()))
                    .filter(value -> criteria.kind() == null || criteria.kind() == value.kind())
                    .filter(value -> criteria.parentId() == null || criteria.parentId().equals(value.parentId()))
                    .filter(value -> criteria.status() == null || criteria.status() == value.status())
                    .filter(value -> criteria.countryCode() == null || criteria.countryCode().equals(value.countryCode()))
                    .filter(value -> criteria.afterId() == null || value.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(FacilityNode::id)).limit((long) criteria.limit() + 1L).toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            return new FacilityPage(filtered.size() > criteria.limit() ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered, next);
        }
    }
}
