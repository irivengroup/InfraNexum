package io.infranexum.itam.partner;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.partner.application.CreatePartnerCommand;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerPage;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerCode;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerNotFoundException;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.itam.partner.ports.PartnerFeaturePolicy;
import io.infranexum.itam.partner.ports.PartnerGovernanceScope;
import io.infranexum.itam.partner.ports.PartnerIdempotencyRepository;
import io.infranexum.itam.partner.ports.PartnerRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Use-case tests for quota, deduplication, lifecycle, idempotency and transactional event publication. */
final class PartnerApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-14T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DomainIdentifier ORG = DomainIdentifier.parse("01900000-0000-7000-8000-000000000001");
    private static final DomainIdentifier SUB = DomainIdentifier.parse("01900000-0000-7000-8000-000000000002");
    private static final DomainIdentifier ACTOR = DomainIdentifier.parse("01900000-0000-7000-8000-000000000003");
    private static final DomainIdentifier CORR = DomainIdentifier.parse("01900000-0000-7000-8000-000000000004");

    private Repository repository;
    private Idempotency idempotency;
    private InMemoryEventStore events;
    private MutableFeatures features;
    private MutableGovernance governance;
    private PartnerApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new Repository();
        idempotency = new Idempotency();
        events = new InMemoryEventStore();
        features = new MutableFeatures(true, 10);
        governance = new MutableGovernance(true, true);
        service = new PartnerApplicationService(
                repository, idempotency, features, governance, events,
                new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 2, 3, 4})), CLOCK);
    }

    @Test
    void createPublishesEventAndReplaysSamePayloadIdempotently() {
        PartnerCommandContext context = context("create-partner-001", "Initial governed registration");
        Partner created = service.create(command("VEN-001", "Vendor One SAS"), context);
        Partner replayed = service.create(command("VEN-001", "Vendor One SAS"), context);
        assertSame(created, replayed);
        assertEquals(1, repository.count());
        assertEquals(1, events.outboxSnapshot().size());
        assertEquals("itam.partner.created.v1", events.outboxSnapshot().get(0).event().eventType().value());
        assertTrue(events.outboxSnapshot().get(0).event().payload().contains("VEN-001"));
    }

    @Test
    void createRejectsCapabilityScopeQuotaCodeDuplicateIdentityAndIdempotencyConflicts() {
        features.enabled = false;
        assertCode("ITAM_PARTNER_CAPABILITY_UNAVAILABLE", () -> service.create(command("VEN-001", "Vendor One SAS"), context("create-0001", "Initial registration")));
        features.enabled = true;
        governance.organizationExists = false;
        assertCode("GOVERNING_ORGANIZATION_INVALID", () -> service.create(command("VEN-001", "Vendor One SAS"), context("create-0002", "Initial registration")));
        governance.organizationExists = true;
        governance.subdivisionExists = false;
        assertCode("GOVERNING_SUBDIVISION_INVALID", () -> service.create(command("VEN-001", "Vendor One SAS"), context("create-0003", "Initial registration")));
        governance.subdivisionExists = true;

        features.limit = 0;
        assertThrows(PartnerQuotaException.class, () -> service.create(command("VEN-001", "Vendor One SAS"), context("create-0004", "Initial registration")));
        features.limit = 10;
        Partner first = service.create(command("VEN-001", "Vendor One SAS"), context("create-0005", "Initial registration"));
        assertCode("PARTNER_CODE_CONFLICT", () -> service.create(command("VEN-001", "Different Legal SAS"), context("create-0006", "Code conflict check")));
        assertCode("PARTNER_DUPLICATE", () -> service.create(command("VEN-002", "Vendor One SAS"), context("create-0007", "Identity conflict check")));
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.create(command("VEN-003", "Vendor Three SAS"), context("create-0005", "Initial registration")));
        assertEquals(first.id(), service.get(first.id()).id());
    }

    @Test
    void lifecycleEnforcesVersionAndEmitsOnlySourceSupportedTransitionEvents() {
        Partner draft = service.create(command("VEN-001", "Vendor One SAS"), context("create-0001", "Initial registration"));
        Partner pending = service.submitApproval(draft.id(), 1, context("submit-0001", "Compliance review completed"));
        assertEquals(PartnerAuthorizationStatus.PENDING_APPROVAL, pending.authorizationStatus());
        assertEquals(1, events.outboxSnapshot().size(), "submit approval must not invent a published integration event");
        assertCode("VERSION_CONFLICT", () -> service.authorize(draft.id(), 1, context("auth-stale-01", "Stale approval attempt")));
        Partner active = service.authorize(draft.id(), 2, context("auth-0000001", "Authorized by ITAM approver"));
        assertEquals(PartnerAuthorizationStatus.ACTIVE, active.authorizationStatus());
        Partner replay = service.authorize(draft.id(), 2, context("auth-0000001", "Authorized by ITAM approver"));
        assertEquals(active.id(), replay.id());
        Partner suspended = service.suspend(active.id(), 3, context("suspend-0001", "Accreditation under investigation"));
        assertEquals(PartnerAuthorizationStatus.SUSPENDED, suspended.authorizationStatus());
        assertEquals(List.of("itam.partner.authorized.v1", "itam.partner.created.v1", "itam.partner.suspended.v1"),
                events.outboxSnapshot().stream().map(record -> record.event().eventType().value()).sorted().toList());
    }

    @Test
    void missingPartnersInvalidVersionsAndInvalidAuthorizationPeriodAreStableBusinessFailures() {
        DomainIdentifier missing = DomainIdentifier.parse("01900000-0000-7000-8000-000000000099");
        assertThrows(PartnerNotFoundException.class, () -> service.get(missing));
        assertThrows(IllegalArgumentException.class, () -> service.submitApproval(missing, 0, context("submit-0001", "Valid reason")));
        Partner draft = service.create(futureCommand(), context("future-create-01", "Future partner registration"));
        Partner pending = service.submitApproval(draft.id(), 1, context("future-submit-1", "Future partner compliance review"));
        assertCode("PARTNER_AUTHORIZATION_PERIOD_INVALID", () -> service.authorize(
                pending.id(), 2, context("future-auth-001", "Premature partner authorization")));
    }

    @Test
    void searchesValidateOrganizationAndDelegateStableFilters() {
        service.create(command("VEN-001", "Vendor One SAS"), context("create-0001", "Initial registration"));
        PartnerSearchCriteria criteria = new PartnerSearchCriteria(ORG, PartnerRole.MANUFACTURER, PartnerAuthorizationStatus.DRAFT, "FR", null, null, null, 20);
        PartnerPage page = service.search(criteria);
        assertEquals(1, page.items().size());
        assertSame(criteria, repository.lastSearch);
        PartnerSearchCriteria global = new PartnerSearchCriteria(null, null, null, null, null, null, null, 20);
        assertEquals(1, service.search(global).items().size());
        governance.organizationExists = false;
        assertThrows(PartnerNotFoundException.class, () -> service.search(criteria));
        assertThrows(NullPointerException.class, () -> service.search(null));
    }


    @Test
    void optionalSubdivisionEmptyRolesAndCrossOperationReplayAreFailClosed() {
        CreatePartnerCommand global = new CreatePartnerCommand(
                ORG, null, "GLO-001", "Global Vendor SAS", "Global Vendor", "FR", Set.of("supplier"),
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of());
        Partner created = service.create(global, context("global-create-01", "Global registration"));
        assertNull(created.governingSubdivisionId());

        CreatePartnerCommand emptyRoles = new CreatePartnerCommand(
                ORG, null, "BAD-001", "No Role Vendor SAS", "No Role Vendor", "FR", Set.of(),
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(emptyRoles,
                context("empty-role-001", "Reject empty roles")));

        Partner pending = service.submitApproval(created.id(), created.version(),
                context("cross-replay-key", "Submit global vendor"));
        assertCode("IDEMPOTENCY_CONFLICT", () -> service.authorize(pending.id(), pending.version(),
                context("cross-replay-key", "Submit global vendor")));
    }

    @Test
    void constructorAndArgumentsRejectNullDependenciesAndInputs() {
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(null, idempotency, features, governance, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, null, features, governance, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, idempotency, null, governance, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, idempotency, features, null, events, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, idempotency, features, governance, null, ids(), CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, idempotency, features, governance, events, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new PartnerApplicationService(repository, idempotency, features, governance, events, ids(), null));
        assertThrows(NullPointerException.class, () -> service.create(null, context("create-0001", "Initial registration")));
        assertThrows(NullPointerException.class, () -> service.create(command("VEN-001", "Vendor One SAS"), null));
        assertThrows(NullPointerException.class, () -> service.get(null));
    }

    @Test
    void transactionExecutionPreservesKnownBusinessCausesAndUnknownFailures() {
        TransactionalEventStore failing = new TransactionalEventStore() {
            @Override public <T> io.infranexum.core.events.TransactionOutcome<T> execute(io.infranexum.core.events.TransactionalWork<T> work) {
                throw new UnsupportedOperationException();
            }
            @Override public List<io.infranexum.core.events.OutboxRecord> claimBatch(String workerId, int limit, Instant now, java.time.Duration lease) { return List.of(); }
            @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant at) {}
            @Override public io.infranexum.core.events.OutboxStatus markFailed(DomainIdentifier eventId, String workerId, Instant at, io.infranexum.core.events.RetryPolicy retry, Throwable failure) { return io.infranexum.core.events.OutboxStatus.DEAD_LETTER; }
        };
        PartnerApplicationService failed = new PartnerApplicationService(repository, idempotency, features, governance, failing, ids(), CLOCK);
        assertThrows(UnsupportedOperationException.class, () -> failed.create(command("VEN-001", "Vendor One SAS"), context("create-0001", "Initial registration")));

        TransactionalEventStore wrapped = new TransactionalEventStore() {
            @Override public <T> io.infranexum.core.events.TransactionOutcome<T> execute(io.infranexum.core.events.TransactionalWork<T> work) {
                throw new TransactionExecutionException("wrapped", new IllegalStateException("database failure"));
            }
            @Override public List<io.infranexum.core.events.OutboxRecord> claimBatch(String workerId, int limit, Instant now, java.time.Duration lease) { return List.of(); }
            @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant at) {}
            @Override public io.infranexum.core.events.OutboxStatus markFailed(DomainIdentifier eventId, String workerId, Instant at, io.infranexum.core.events.RetryPolicy retry, Throwable failure) { return io.infranexum.core.events.OutboxStatus.DEAD_LETTER; }
        };
        PartnerApplicationService unknown = new PartnerApplicationService(repository, idempotency, features, governance, wrapped, ids(), CLOCK);
        assertThrows(TransactionExecutionException.class, () -> unknown.create(command("VEN-001", "Vendor One SAS"), context("create-0002", "Initial registration")));

        PartnerApplicationService notFound = new PartnerApplicationService(repository, idempotency, features, governance,
                failingStore(new PartnerNotFoundException()), ids(), CLOCK);
        assertThrows(PartnerNotFoundException.class, () -> notFound.create(command("VEN-002", "Vendor Two SAS"), context("create-0003", "Wrapped not found")));
        PartnerApplicationService invalid = new PartnerApplicationService(repository, idempotency, features, governance,
                failingStore(new IllegalArgumentException("invalid")), ids(), CLOCK);
        assertThrows(IllegalArgumentException.class, () -> invalid.create(command("VEN-003", "Vendor Three SAS"), context("create-0004", "Wrapped invalid")));
    }

    private static TransactionalEventStore failingStore(Throwable cause) {
        return new TransactionalEventStore() {
            @Override public <T> io.infranexum.core.events.TransactionOutcome<T> execute(io.infranexum.core.events.TransactionalWork<T> work) {
                throw new TransactionExecutionException("wrapped", cause);
            }
            @Override public List<io.infranexum.core.events.OutboxRecord> claimBatch(String workerId, int limit, Instant now, java.time.Duration lease) { return List.of(); }
            @Override public void markPublished(DomainIdentifier eventId, String workerId, Instant at) {}
            @Override public io.infranexum.core.events.OutboxStatus markFailed(DomainIdentifier eventId, String workerId, Instant at, io.infranexum.core.events.RetryPolicy retry, Throwable failure) { return io.infranexum.core.events.OutboxStatus.DEAD_LETTER; }
        };
    }

    private static UuidV7Generator ids() { return new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {9, 8, 7, 6})); }

    private static CreatePartnerCommand command(String code, String legalName) {
        return new CreatePartnerCommand(
                ORG, SUB, code, legalName, legalName, "FR", Set.of("manufacturer"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 12, 31), null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    private static CreatePartnerCommand futureCommand() {
        return new CreatePartnerCommand(
                ORG, SUB, "FUT-001", "Future Partner SAS", "Future Partner", "FR", Set.of("supplier"),
                LocalDate.of(2027, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static PartnerCommandContext context(String key, String reason) { return new PartnerCommandContext(ACTOR, CORR, key, reason); }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        PartnerConflictException failure = assertThrows(PartnerConflictException.class, executable);
        assertEquals(code, failure.code());
    }

    private static final class MutableFeatures implements PartnerFeaturePolicy {
        boolean enabled;
        long limit;
        MutableFeatures(boolean enabled, long limit) { this.enabled = enabled; this.limit = limit; }
        @Override public boolean partnerCatalogueEnabled() { return enabled; }
        @Override public long partnerLimit() { return limit; }
    }

    private static final class MutableGovernance implements PartnerGovernanceScope {
        boolean organizationExists;
        boolean subdivisionExists;
        MutableGovernance(boolean organizationExists, boolean subdivisionExists) {
            this.organizationExists = organizationExists; this.subdivisionExists = subdivisionExists;
        }
        @Override public boolean organizationExists(DomainIdentifier ignored) { return organizationExists; }
        @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) { return subdivisionExists; }
    }

    private static final class Idempotency implements PartnerIdempotencyRepository {
        private final Map<String, Record> values = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void insert(Record record) { values.put(record.key(), record); }
    }

    private static final class Repository implements PartnerRepository {
        private final Map<DomainIdentifier, Partner> values = new LinkedHashMap<>();
        PartnerSearchCriteria lastSearch;
        @Override public long count() { return values.size(); }
        @Override public boolean existsByCode(DomainIdentifier organization, PartnerCode code) {
            return values.values().stream().anyMatch(value -> value.governingOrganizationId().equals(organization) && value.code().equals(code));
        }
        @Override public boolean hasIdentityTokenCollision(DomainIdentifier organization, Set<String> tokens) {
            return values.values().stream().filter(value -> value.governingOrganizationId().equals(organization))
                    .anyMatch(value -> intersects(value.identityTokens(), tokens));
        }
        @Override public Optional<Partner> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public void insert(Partner partner) { values.put(partner.id(), partner); }
        @Override public void updateLifecycle(Partner partner, long expectedVersion) {
            Partner current = values.get(partner.id());
            if (current == null || current.version() != expectedVersion) throw new PartnerConflictException("VERSION_CONFLICT", "partner version changed");
            values.put(partner.id(), partner);
        }
        @Override public PartnerPage search(PartnerSearchCriteria criteria) {
            lastSearch = criteria;
            List<Partner> filtered = values.values().stream()
                    .filter(value -> criteria.governingOrganizationId() == null || value.governingOrganizationId().equals(criteria.governingOrganizationId()))
                    .filter(value -> criteria.role() == null || value.roles().contains(criteria.role()))
                    .filter(value -> criteria.authorizationStatus() == null || value.authorizationStatus() == criteria.authorizationStatus())
                    .filter(value -> criteria.countryCode() == null || value.countryCode().equals(criteria.countryCode()))
                    .filter(value -> criteria.afterId() == null || value.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(Partner::id)).limit((long) criteria.limit() + 1).toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            return new PartnerPage(filtered.size() > criteria.limit() ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered, next);
        }
        private static boolean intersects(Set<String> left, Set<String> right) {
            Set<String> copy = new LinkedHashSet<>(left); copy.retainAll(right); return !copy.isEmpty();
        }
    }
}
