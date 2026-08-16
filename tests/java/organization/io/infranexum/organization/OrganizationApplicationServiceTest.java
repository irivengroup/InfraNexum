package io.infranexum.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.organization.application.CreateOrganizationCommand;
import io.infranexum.organization.application.CreateSubdivisionCommand;
import io.infranexum.organization.application.CreateTemporalScopeCommand;
import io.infranexum.organization.application.OrganizationApplicationService;
import io.infranexum.organization.application.OrganizationCommandContext;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.TemporalScope;
import io.infranexum.organization.ports.IdempotencyRepository;
import io.infranexum.organization.ports.OrganizationFeaturePolicy;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import io.infranexum.organization.ports.TemporalScopeRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrganizationApplicationServiceTest {
    private ServiceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ServiceFixture();
    }

    @Test
    void createsOrganizationIdempotentlyAndEmitsOneEvent() {
        var command = organization("ORG-001");
        var context = fixture.context("idem-org-0001");

        Organization first = fixture.service.createOrganization(command, context);
        Organization replay = fixture.service.createOrganization(command, context);

        assertEquals(first.id(), replay.id());
        assertEquals(OrganizationState.ACTIVE, first.state());
        assertEquals(1, fixture.events.outboxSnapshot().size());
    }

    @Test
    void rejectsDifferentPayloadForSameKeyAndEnforcesQuota() {
        var context = fixture.context("idem-org-0002");
        fixture.service.createOrganization(organization("ORG-001"), context);

        assertThrows(
                OrganizationConflictException.class,
                () -> fixture.service.createOrganization(organization("ORG-002"), context));

        fixture.features.organizationLimit = 1;
        assertThrows(
                OrganizationQuotaException.class,
                () -> fixture.service.createOrganization(
                        organization("ORG-003"), fixture.context("idem-org-0003")));
    }

    @Test
    void subdivisionRespectsOwnerQuotaAndHierarchyDepth() {
        Organization organization = fixture.service.createOrganization(
                organization("ORG-001"), fixture.context("idem-org-0004"));
        Subdivision root = fixture.service.createSubdivision(
                new CreateSubdivisionCommand(
                        organization.id(), "SUB-001", "Root", null, "department", null),
                fixture.context("idem-sub-0001"));

        fixture.features.hierarchyDepth = 1;
        assertThrows(
                OrganizationQuotaException.class,
                () -> fixture.service.createSubdivision(
                        new CreateSubdivisionCommand(
                                organization.id(), "SUB-002", "Child", null, "project", root.id()),
                        fixture.context("idem-sub-0002")));

        fixture.features.hierarchyDepth = 3;
        Subdivision child = fixture.service.createSubdivision(
                new CreateSubdivisionCommand(
                        organization.id(), "SUB-002", "Child", null, "project", root.id()),
                fixture.context("idem-sub-0003"));
        assertEquals(root.id(), child.parentSubdivisionId());
    }

    @Test
    void temporalScopeAndTransitionsAreIdempotent() {
        Organization organization = fixture.service.createOrganization(
                organization("ORG-001"), fixture.context("idem-org-0005"));
        Instant start = fixture.clock.instant();
        var scopeContext = fixture.context("idem-scp-0001");
        var scopeCommand = new CreateTemporalScopeCommand(
                organization.id(), null, "data", start, start.plusSeconds(60));

        TemporalScope firstScope = fixture.service.createTemporalScope(scopeCommand, scopeContext);
        TemporalScope replayedScope = fixture.service.createTemporalScope(scopeCommand, scopeContext);
        assertEquals(firstScope.id(), replayedScope.id());
        assertEquals(firstScope.id(), fixture.service.effectiveScopes(organization.id(), start).getFirst().id());

        var suspendContext = fixture.context("idem-org-0006");
        Organization suspended = fixture.service.suspend(
                organization.id(), organization.version(), suspendContext);
        Organization replayedSuspend = fixture.service.suspend(
                organization.id(), organization.version(), suspendContext);
        assertEquals(suspended.version(), replayedSuspend.version());
        assertEquals(OrganizationState.SUSPENDED, replayedSuspend.state());

        Organization resumed = fixture.service.resume(
                organization.id(),
                suspended.version(),
                fixture.context("idem-org-0007"));
        assertEquals(OrganizationState.ACTIVE, resumed.state());
    }

    @Test
    void profileFeatureDenialsFailClosed() {
        fixture.features.subdivisionsSupported = false;
        Organization organization = fixture.service.createOrganization(
                organization("ORG-001"), fixture.context("idem-org-0008"));
        assertThrows(
                OrganizationConflictException.class,
                () -> fixture.service.createSubdivision(
                        new CreateSubdivisionCommand(
                                organization.id(), "SUB-001", "Blocked", null, "site", null),
                        fixture.context("idem-sub-0004")));
    }

    private static CreateOrganizationCommand organization(String code) {
        return new CreateOrganizationCommand(
                code,
                "Organization " + code,
                "Legal " + code,
                "FR",
                "fr",
                "Europe/Paris",
                "EUR",
                null);
    }

    static final class ServiceFixture {
        final Clock clock = Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC);
        final UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom(new byte[] {1, 2, 3}));
        final InMemoryEventStore events = new InMemoryEventStore();
        final Features features = new Features();
        final Organizations organizations = new Organizations();
        final Subdivisions subdivisions = new Subdivisions();
        final Scopes scopes = new Scopes();
        final Dedup dedup = new Dedup();
        final OrganizationApplicationService service = new OrganizationApplicationService(
                organizations, subdivisions, scopes, dedup, features, events, ids, clock);

        OrganizationCommandContext context(String key) {
            return new OrganizationCommandContext("test-actor", ids.next(), key, "test");
        }
    }

    static final class Features implements OrganizationFeaturePolicy {
        long organizationLimit = 10;
        long subdivisionLimit = 500;
        long hierarchyDepth = 3;
        boolean subdivisionsSupported = true;

        @Override
        public boolean supportsOrganizationHierarchy() {
            return true;
        }

        @Override
        public boolean supportsSubdivisions() {
            return subdivisionsSupported;
        }

        @Override
        public long organizationLimit() {
            return organizationLimit;
        }

        @Override
        public long subdivisionLimit() {
            return subdivisionLimit;
        }

        @Override
        public long hierarchyDepthLimit() {
            return hierarchyDepth;
        }
    }

    static final class Organizations implements OrganizationRepository {
        final Map<DomainIdentifier, Organization> values = new HashMap<>();

        @Override
        public long count() {
            return values.values().stream().filter(o -> o.state() != OrganizationState.DELETED).count();
        }

        @Override
        public boolean existsByCode(OrganizationCode code) {
            return values.values().stream().anyMatch(o -> o.code().equals(code));
        }

        @Override
        public Optional<Organization> findById(DomainIdentifier id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Optional<Organization> findByCode(OrganizationCode code) {
            return values.values().stream().filter(o -> o.code().equals(code)).findFirst();
        }

        @Override
        public void insert(Organization organization) {
            values.put(organization.id(), organization);
        }

        @Override
        public void update(Organization organization, long expectedVersion) {
            Organization current = values.get(organization.id());
            if (current == null || current.version() != expectedVersion) {
                throw new OrganizationConflictException("VERSION_CONFLICT", "changed");
            }
            values.put(organization.id(), organization);
        }

        @Override
        public List<Organization> search(
                String query, OrganizationState state, int offset, int limit) {
            return values.values().stream()
                    .filter(value -> state == null || value.state() == state)
                    .sorted(Comparator.comparing(value -> value.code().value()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    static final class Subdivisions implements SubdivisionRepository {
        final Map<DomainIdentifier, Subdivision> values = new HashMap<>();

        @Override
        public long countByOrganization(DomainIdentifier organizationId) {
            return values.values().stream()
                    .filter(value -> value.organizationId().equals(organizationId))
                    .count();
        }

        @Override
        public boolean existsCode(DomainIdentifier organizationId, SubdivisionCode code) {
            return values.values().stream().anyMatch(value ->
                    value.organizationId().equals(organizationId) && value.code().equals(code));
        }

        @Override
        public Optional<Subdivision> findById(
                DomainIdentifier organizationId, DomainIdentifier id) {
            return Optional.ofNullable(values.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public void insert(Subdivision subdivision) {
            values.put(subdivision.id(), subdivision);
        }

        @Override
        public void update(Subdivision subdivision, long expectedVersion) {
            values.put(subdivision.id(), subdivision);
        }

        @Override
        public List<Subdivision> list(
                DomainIdentifier organizationId, int offset, int limit) {
            return values.values().stream()
                    .filter(value -> value.organizationId().equals(organizationId))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    static final class Scopes implements TemporalScopeRepository {
        final List<TemporalScope> values = new ArrayList<>();

        @Override
        public void insert(TemporalScope scope) {
            values.add(scope);
        }

        @Override
        public Optional<TemporalScope> findById(
                DomainIdentifier organizationId, DomainIdentifier id) {
            return values.stream()
                    .filter(scope -> scope.organizationId().equals(organizationId) && scope.id().equals(id))
                    .findFirst();
        }

        @Override
        public List<TemporalScope> effective(DomainIdentifier organizationId, Instant at) {
            return values.stream()
                    .filter(scope -> scope.organizationId().equals(organizationId) && scope.effectiveAt(at))
                    .toList();
        }
    }

    static final class Dedup implements IdempotencyRepository {
        final Map<String, Record> values = new HashMap<>();

        @Override
        public Optional<Record> find(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void insert(Record record) {
            values.put(record.key(), record);
        }
    }
}
