package io.infranexum.organization;

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

/** Dependency-free executable smoke for the Organization foundation application boundary. */
public final class OrganizationFoundationSmoke {
    private OrganizationFoundationSmoke() {}

    public static void main(String[] args) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom(new byte[] {1, 2, 3, 4}));
        InMemoryEventStore events = new InMemoryEventStore();
        Organizations organizations = new Organizations();
        Subdivisions subdivisions = new Subdivisions();
        Scopes scopes = new Scopes();
        Dedup dedup = new Dedup();
        OrganizationApplicationService service = new OrganizationApplicationService(
                organizations,
                subdivisions,
                scopes,
                dedup,
                new Features(),
                events,
                ids,
                clock);

        OrganizationCommandContext orgContext = context(ids, "org-smoke-0001");
        CreateOrganizationCommand createOrganization = new CreateOrganizationCommand(
                "LAB-FR",
                "InfraNexum Lab France",
                "InfraNexum Lab France",
                "FR",
                "fr-FR",
                "Europe/Paris",
                "EUR",
                null);
        Organization organization = service.createOrganization(createOrganization, orgContext);
        Organization replay = service.createOrganization(createOrganization, orgContext);
        check(organization.id().equals(replay.id()), "organization idempotency failed");
        check(organization.state() == OrganizationState.ACTIVE, "organization must be active");

        Subdivision subdivision = service.createSubdivision(
                new CreateSubdivisionCommand(
                        organization.id(), "OPS", "Operations", null, "department", null),
                context(ids, "sub-smoke-0001"));
        check(subdivision.organizationId().equals(organization.id()), "subdivision scope leaked");

        Instant start = clock.instant();
        TemporalScope scope = service.createTemporalScope(
                new CreateTemporalScopeCommand(
                        organization.id(), subdivision.id(), "operational", start, start.plusSeconds(3600)),
                context(ids, "scope-smoke-0001"));
        check(service.effectiveScopes(organization.id(), start).stream()
                .anyMatch(candidate -> candidate.id().equals(scope.id())), "effective scope missing");
        check(events.outboxSnapshot().size() == 3, "expected exactly three emitted events");

        System.out.println("organization-foundation-smoke: PASS");
    }

    private static OrganizationCommandContext context(UuidV7Generator ids, String key) {
        return new OrganizationCommandContext("smoke-actor", ids.next(), key, "smoke");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static final class Features implements OrganizationFeaturePolicy {
        @Override public boolean supportsOrganizationHierarchy() { return true; }
        @Override public boolean supportsSubdivisions() { return true; }
        @Override public long organizationLimit() { return 10; }
        @Override public long subdivisionLimit() { return 100; }
        @Override public long hierarchyDepthLimit() { return 3; }
    }

    static final class Organizations implements OrganizationRepository {
        private final Map<DomainIdentifier, Organization> values = new HashMap<>();

        @Override public long count() { return values.size(); }
        @Override public boolean existsByCode(OrganizationCode code) {
            return values.values().stream().anyMatch(value -> value.code().equals(code));
        }
        @Override public Optional<Organization> findById(DomainIdentifier id) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public Optional<Organization> findByCode(OrganizationCode code) {
            return values.values().stream().filter(value -> value.code().equals(code)).findFirst();
        }
        @Override public void insert(Organization organization) { values.put(organization.id(), organization); }
        @Override public void update(Organization organization, long expectedVersion) {
            Organization current = values.get(organization.id());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("optimistic version mismatch");
            }
            values.put(organization.id(), organization);
        }
        @Override public List<Organization> search(
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
        private final Map<DomainIdentifier, Subdivision> values = new HashMap<>();

        @Override public long countByOrganization(DomainIdentifier organizationId) {
            return values.values().stream()
                    .filter(value -> value.organizationId().equals(organizationId))
                    .count();
        }
        @Override public boolean existsCode(DomainIdentifier organizationId, SubdivisionCode code) {
            return values.values().stream().anyMatch(value ->
                    value.organizationId().equals(organizationId) && value.code().equals(code));
        }
        @Override public Optional<Subdivision> findById(
                DomainIdentifier organizationId, DomainIdentifier id) {
            return Optional.ofNullable(values.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }
        @Override public void insert(Subdivision subdivision) { values.put(subdivision.id(), subdivision); }
        @Override public void update(Subdivision subdivision, long expectedVersion) {
            values.put(subdivision.id(), subdivision);
        }
        @Override public List<Subdivision> list(DomainIdentifier organizationId, int offset, int limit) {
            return values.values().stream()
                    .filter(value -> value.organizationId().equals(organizationId))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    static final class Scopes implements TemporalScopeRepository {
        private final List<TemporalScope> values = new ArrayList<>();
        @Override public void insert(TemporalScope scope) { values.add(scope); }
        @Override public Optional<TemporalScope> findById(
                DomainIdentifier organizationId, DomainIdentifier id) {
            return values.stream()
                    .filter(scope -> scope.organizationId().equals(organizationId) && scope.id().equals(id))
                    .findFirst();
        }
        @Override public List<TemporalScope> effective(DomainIdentifier organizationId, Instant at) {
            return values.stream()
                    .filter(scope -> scope.organizationId().equals(organizationId) && scope.effectiveAt(at))
                    .toList();
        }
    }

    static final class Dedup implements IdempotencyRepository {
        private final Map<String, Record> values = new HashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void insert(Record record) { values.put(record.key(), record); }
    }
}
