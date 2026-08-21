package io.infranexum.dcim.facility;

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

/** Dependency-free lifecycle and hierarchy smoke for PGM-07-E04 DCIM facilities. */
public final class DcimFacilitySmoke {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private DcimFacilitySmoke() {}

    public static void main(String[] args) {
        Repository repository = new Repository();
        Idempotency idempotency = new Idempotency();
        InMemoryEventStore events = new InMemoryEventStore();
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {8, 1, 0, 4}));
        DomainIdentifier organization = ids.next();
        DomainIdentifier subdivision = ids.next();
        DomainIdentifier otherSubdivision = ids.next();
        DomainIdentifier actor = ids.next();
        DomainIdentifier correlation = ids.next();
        Limits limits = new Limits(true, 2L);
        FacilityApplicationService service = new FacilityApplicationService(
                repository, idempotency, limits, new Scope(organization, subdivision), events, ids, CLOCK);

        CreateFacilityCommand siteCommand = site(organization, subdivision, "PAR01", "Paris primary site");
        FacilityCommandContext createSite = context(actor, correlation, "dcim-site-create-001", "Register primary site");
        FacilityNode site = service.create(siteCommand, createSite);
        require(site.kind() == FacilityKind.SITE && site.status() == FacilityStatus.DRAFT, "site must start as draft");
        require("10 Rue de Rivoli".equals(site.addressLine1()) && "75001".equals(site.postalCode()) && "Paris".equals(site.city()), "site address was not retained");
        require("FR".equals(site.countryCode()) && "Europe/Paris".equals(site.timezone()), "site geography was not retained");
        require(site.scopeId().equals(subdivision), "site uniqueness scope must be subdivision");
        require(service.create(siteCommand, createSite).id().equals(site.id()), "idempotent create must replay the aggregate");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.create(site(organization, subdivision, "PAR02", "Different site"), createSite));
        expectCode("DCIM_CODE_DUPLICATE", () -> service.create(site(organization, subdivision, "PAR01", "Duplicate site"), context(actor, correlation, "dcim-site-create-002", "Verify duplicate code")));
        expectCode("DCIM_SUBDIVISION_INVALID", () -> service.create(site(organization, otherSubdivision, "BAD01", "Foreign subdivision"), context(actor, correlation, "dcim-site-create-003", "Verify weak scope reference")));

        FacilityNode activeSite = service.changeStatus(site.id(), 1, FacilityStatus.ACTIVE,
                context(actor, correlation, "dcim-site-status-001", "Activate governed site"));
        require(activeSite.status() == FacilityStatus.ACTIVE && activeSite.version() == 2, "site activation failed");
        expectCode("VERSION_CONFLICT", () -> service.update(site.id(), 1, update("Stale site update"), context(actor, correlation, "dcim-site-update-stale", "Verify optimistic concurrency")));

        FacilityNode building = service.create(building(organization, subdivision, activeSite.id(), "BLD01", "Building one"),
                context(actor, correlation, "dcim-building-create-001", "Register building"));
        require(building.parentId().equals(activeSite.id()) && building.scopeId().equals(activeSite.id()), "building hierarchy is incorrect");
        building = service.changeStatus(building.id(), 1, FacilityStatus.ACTIVE,
                context(actor, correlation, "dcim-building-status-001", "Activate building"));

        FacilityNode floor = service.create(floor(organization, subdivision, building.id(), "F01", "Ground floor", 1),
                context(actor, correlation, "dcim-floor-create-001", "Register floor"));
        floor = service.changeStatus(floor.id(), 1, FacilityStatus.ACTIVE,
                context(actor, correlation, "dcim-floor-status-001", "Activate floor"));

        FacilityNode room = service.create(room(organization, subdivision, floor.id(), "ROOM01", "Secure room"),
                context(actor, correlation, "dcim-room-create-001", "Register room"));
        room = service.changeStatus(room.id(), 1, FacilityStatus.ACTIVE,
                context(actor, correlation, "dcim-room-status-001", "Activate room"));
        require("secure".equals(room.accessRestriction()), "room access restriction not normalized");

        FacilityNode zone = service.create(zone(organization, subdivision, room.id(), "COOL01", "Cooling zone"),
                context(actor, correlation, "dcim-zone-create-001", "Register technical zone"));
        require(zone.scopeId().equals(activeSite.id()), "zone uniqueness scope must be the site root");
        zone = service.changeStatus(zone.id(), 1, FacilityStatus.ACTIVE,
                context(actor, correlation, "dcim-zone-status-001", "Activate technical zone"));
        require("cooling".equals(zone.zoneType()), "zone type was not preserved");

        expectCode("DCIM_SITE_ARCHIVE_BLOCKED", () -> service.changeStatus(activeSite.id(), 2, FacilityStatus.ARCHIVED,
                context(actor, correlation, "dcim-site-archive-blocked", "Verify active child protection")));
        expectCode("DCIM_PARENT_KIND_INVALID", () -> service.create(
                floor(organization, subdivision, activeSite.id(), "BADF1", "Invalid floor parent", 2),
                context(actor, correlation, "dcim-floor-invalid-parent", "Verify hierarchy kind")));

        FacilityPage rooms = service.search(new FacilitySearchCriteria(
                organization, subdivision, FacilityKind.ROOM, floor.id(), FacilityStatus.ACTIVE, null, null, 50));
        require(rooms.items().size() == 1 && rooms.items().get(0).id().equals(room.id()), "room search is not hierarchy-scoped");
        FacilityPage frenchSites = service.search(new FacilitySearchCriteria(
                organization, subdivision, FacilityKind.SITE, null, null, "fr", null, 50));
        require(frenchSites.items().size() == 1 && frenchSites.items().get(0).id().equals(site.id()), "site country filter is not normalized");

        FacilityNode locked = service.changeStatus(room.id(), 2, FacilityStatus.LOCKED,
                context(actor, correlation, "dcim-room-lock-001", "Lock secure room"));
        require(locked.status() == FacilityStatus.LOCKED, "room lock transition failed");
        expectCode("DCIM_STATUS_TRANSITION_INVALID", () -> service.changeStatus(locked.id(), 3, FacilityStatus.MAINTENANCE,
                context(actor, correlation, "dcim-room-invalid-transition", "Verify invalid lifecycle transition")));

        require(events.outboxSnapshot().stream().anyMatch(record -> "dcim.site.created.v1".equals(record.event().eventType().value())), "site created event missing");
        require(events.outboxSnapshot().stream().anyMatch(record -> "dcim.room.status_changed.v1".equals(record.event().eventType().value())), "room status event missing");
        require(events.outboxSnapshot().stream().anyMatch(record -> "dcim.room.locked.v1".equals(record.event().eventType().value())), "room locked event missing");

        Repository automaticRepository = new Repository();
        Idempotency automaticIdempotency = new Idempotency();
        UuidV7Generator automaticIds = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {8, 1, 0, 5}));
        FacilityApplicationService automaticService = new FacilityApplicationService(
                automaticRepository, automaticIdempotency, new Limits(true, 10L), new Scope(organization, subdivision),
                new InMemoryEventStore(), automaticIds, CLOCK);
        CreateFacilityCommand automaticCommand = site(organization, subdivision, null, "Paris automatic site");
        FacilityCommandContext automaticContext = context(actor, correlation, "dcim-site-auto-001", "Register automatically coded site");
        FacilityNode automaticSite = automaticService.create(automaticCommand, automaticContext);
        FacilityNode automaticReplay = automaticService.create(automaticCommand, automaticContext);
        require(automaticReplay.id().equals(automaticSite.id()) && automaticReplay.code().equals(automaticSite.code()),
                "automatic code must survive exact idempotent replay");
        require(automaticSite.code().value().startsWith("PARIS-AUTOMATIC-SITE-"), "automatic site code must be memorable");

        service.create(site(organization, subdivision, "PAR02", "Paris secondary site"),
                context(actor, correlation, "dcim-site-create-004", "Register second site"));
        expect(FacilityQuotaException.class, () -> service.create(site(organization, subdivision, "PAR03", "Paris overflow site"),
                context(actor, correlation, "dcim-site-create-005", "Verify site quota")));

        FacilityApplicationService disabled = new FacilityApplicationService(
                repository, idempotency, new Limits(false, 2L), new Scope(organization, subdivision), events, ids, CLOCK);
        expectCode("DCIM_FACILITY_CAPABILITY_UNAVAILABLE", () -> disabled.get(site.id()));
        expect(IllegalArgumentException.class, () -> new FacilitySearchCriteria(organization, subdivision, FacilityKind.SITE, null, null, null, null, 201));
        expect(IllegalArgumentException.class, () -> new FacilitySearchCriteria(organization, subdivision, FacilityKind.ROOM, null, null, "FR", null, 50));

        System.out.println("java-dcim-facility-smoke: PASS");
    }

    private static CreateFacilityCommand site(DomainIdentifier org, DomainIdentifier subdivision, String code, String name) {
        return new CreateFacilityCommand(FacilityKind.SITE, org, subdivision, null, code, name,
                "10 Rue de Rivoli", null, "75001", "Paris", "FR", "Europe/Paris",
                new BigDecimal("48.8566000"), new BigDecimal("2.3522000"), null, null, null, null, null, null, null,
                "Primary DCIM site");
    }

    private static CreateFacilityCommand building(DomainIdentifier org, DomainIdentifier subdivision, DomainIdentifier parent, String code, String name) {
        return new CreateFacilityCommand(FacilityKind.BUILDING, org, subdivision, parent, code, name,
                null, null, null, null, null, null, new BigDecimal("48.8567000"), new BigDecimal("2.3523000"), 4, null, new BigDecimal("1200"), null,
                null, null, null, "Production building");
    }

    private static CreateFacilityCommand floor(DomainIdentifier org, DomainIdentifier subdivision, DomainIdentifier parent, String code, String name, int level) {
        return new CreateFacilityCommand(FacilityKind.FLOOR, org, subdivision, parent, code, name,
                null, null, null, null, null, null, null, null, null, level, new BigDecimal("300"),
                new BigDecimal("3.5"), new BigDecimal("180"), null, null, "Facility floor");
    }

    private static CreateFacilityCommand room(DomainIdentifier org, DomainIdentifier subdivision, DomainIdentifier parent, String code, String name) {
        return new CreateFacilityCommand(FacilityKind.ROOM, org, subdivision, parent, code, name,
                null, null, null, null, null, null, null, null, null, null, new BigDecimal("80"), null,
                new BigDecimal("120"), "secure", null, "Server room");
    }

    private static CreateFacilityCommand zone(DomainIdentifier org, DomainIdentifier subdivision, DomainIdentifier parent, String code, String name) {
        return new CreateFacilityCommand(FacilityKind.ZONE, org, subdivision, parent, code, name,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, "cooling", "Cooling technical zone");
    }

    private static io.infranexum.dcim.facility.application.UpdateFacilityCommand update(String name) {
        return new io.infranexum.dcim.facility.application.UpdateFacilityCommand(
                name, "11 Rue de Rivoli", null, "75001", "Paris", "FR", "Europe/Paris", null, null,
                null, null, null, null, null, null, null, "Updated facility");
    }

    private static FacilityCommandContext context(DomainIdentifier actor, DomainIdentifier correlation, String key, String reason) {
        return new FacilityCommandContext(actor, correlation, key, reason);
    }

    private static final class Limits implements FacilityFeaturePolicy {
        private final boolean enabled;
        private final long siteLimit;
        private Limits(boolean enabled, long siteLimit) { this.enabled = enabled; this.siteLimit = siteLimit; }
        @Override public boolean facilitiesEnabled() { return enabled; }
        @Override public long limit(FacilityKind kind) { return kind == FacilityKind.SITE ? siteLimit : 100; }
    }

    private record Scope(DomainIdentifier organization, DomainIdentifier subdivision) implements FacilityScopePolicy {
        @Override public void requireActiveScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
            if (!organization.equals(organizationId)) throw new FacilityConflictException("DCIM_ORGANIZATION_INVALID", "organization unavailable");
            if (!subdivision.equals(subdivisionId)) throw new FacilityConflictException("DCIM_SUBDIVISION_INVALID", "subdivision unavailable");
        }
    }

    private static final class Idempotency implements FacilityIdempotencyRepository {
        private final Map<String, Record> records = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(records.get(key)); }
        @Override public void insert(Record record) {
            if (records.putIfAbsent(record.key(), record) != null) throw new IllegalStateException("duplicate idempotency key");
        }
    }

    private static final class Repository implements FacilityRepository {
        private final Map<DomainIdentifier, FacilityNode> values = new LinkedHashMap<>();
        @Override public long count(FacilityKind kind) { return values.values().stream().filter(v -> v.kind() == kind).count(); }
        @Override public boolean existsByScopeCode(FacilityKind kind, DomainIdentifier scopeId, FacilityCode code) {
            return values.values().stream().anyMatch(v -> v.kind() == kind && v.scopeId().equals(scopeId) && v.code().equals(code));
        }
        @Override public Optional<FacilityNode> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public long activeBuildingsForSite(DomainIdentifier siteId) {
            return values.values().stream().filter(v -> siteId.equals(v.parentId()) && v.kind() == FacilityKind.BUILDING
                    && v.status() == FacilityStatus.ACTIVE).count();
        }
        @Override public void insert(FacilityNode node) {
            if (values.putIfAbsent(node.id(), node) != null) throw new IllegalStateException("duplicate facility id");
        }
        @Override public void update(FacilityNode node, long expectedVersion) {
            FacilityNode current = values.get(node.id());
            if (current == null || current.version() != expectedVersion) throw new FacilityConflictException("VERSION_CONFLICT", "facility version changed");
            values.put(node.id(), node);
        }
        @Override public FacilityPage search(FacilitySearchCriteria criteria) {
            List<FacilityNode> filtered = values.values().stream()
                    .filter(v -> criteria.organizationId() == null || v.organizationId().equals(criteria.organizationId()))
                    .filter(v -> criteria.subdivisionId() == null || v.subdivisionId().equals(criteria.subdivisionId()))
                    .filter(v -> criteria.kind() == null || v.kind() == criteria.kind())
                    .filter(v -> criteria.parentId() == null || criteria.parentId().equals(v.parentId()))
                    .filter(v -> criteria.status() == null || v.status() == criteria.status())
                    .filter(v -> criteria.countryCode() == null || criteria.countryCode().equals(v.countryCode()))
                    .filter(v -> criteria.afterId() == null || v.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(FacilityNode::id))
                    .limit((long) criteria.limit() + 1L)
                    .toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            List<FacilityNode> page = filtered.size() > criteria.limit() ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered;
            return new FacilityPage(page, next);
        }
    }

    private static void expectCode(String code, ThrowingAction action) {
        try {
            action.run();
        } catch (FacilityConflictException error) {
            require(code.equals(error.code()), "unexpected DCIM code: " + error.code());
            return;
        } catch (Exception error) {
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected FacilityConflictException " + code);
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
