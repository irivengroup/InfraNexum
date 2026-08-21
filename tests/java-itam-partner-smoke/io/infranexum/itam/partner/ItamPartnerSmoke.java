package io.infranexum.itam.partner;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.itam.partner.application.CreatePartnerCommand;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerPage;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
import io.infranexum.itam.partner.domain.PartnerRole;
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

/** Dependency-free lifecycle smoke for PGM-07-E01 governed Partner catalogues. */
public final class ItamPartnerSmoke {
    private static final Instant NOW = Instant.parse("2026-08-14T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private ItamPartnerSmoke() {}

    public static void main(String[] args) {
        Repository repository = new Repository();
        Idempotency idempotency = new Idempotency();
        InMemoryEventStore events = new InMemoryEventStore();
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {7, 7, 7, 7}));
        DomainIdentifier organization = ids.next();
        DomainIdentifier subdivision = ids.next();
        DomainIdentifier actor = ids.next();
        DomainIdentifier correlation = ids.next();
        PartnerApplicationService service = new PartnerApplicationService(
                repository,
                idempotency,
                new FeaturePolicy(true, 2),
                new Governance(organization, subdivision),
                events,
                ids,
                CLOCK);

        CreatePartnerCommand command = command(organization, subdivision, null, "Acme Infrastructure SAS", "Acme");
        PartnerCommandContext createContext = context(actor, correlation, "create-acme-0001", "Initial ITAM Partner registration");
        Partner draft = service.create(command, createContext);
        require(draft.authorizationStatus() == PartnerAuthorizationStatus.DRAFT, "partner must start as draft");
        require(draft.version() == 1, "draft version must start at one");
        require(draft.code().value().startsWith("ACME-"), "partner code must be generated from display name");
        require(draft.roles().contains(PartnerRole.MANUFACTURER), "manufacturer role was not preserved");
        require(draft.identityTokens().stream().anyMatch(value -> value.startsWith("external:")), "external identity token missing");

        Partner replay = service.create(command, createContext);
        require(replay.id().equals(draft.id()), "idempotent create did not replay the same aggregate");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.create(
                command(organization, subdivision, "VEND-002", "Different Partner SAS", "Different"), createContext));
        expectCode("PARTNER_DUPLICATE", () -> service.create(
                command(organization, subdivision, "VEND-002", "Acme Infrastructure SAS", "Acme Duplicate"),
                context(actor, correlation, "create-acme-0002", "Duplicate candidate validation")));

        Partner pending = service.submitApproval(
                draft.id(), 1, context(actor, correlation, "submit-acme-0001", "Submitted after compliance review"));
        require(pending.authorizationStatus() == PartnerAuthorizationStatus.PENDING_APPROVAL && pending.version() == 2,
                "submit approval lifecycle failed");
        expectCode("VERSION_CONFLICT", () -> service.authorize(
                draft.id(), 1, context(actor, correlation, "auth-acme-stale", "Approval using stale state version")));
        Partner active = service.authorize(
                draft.id(), 2, context(actor, correlation, "auth-acme-0001", "Approved by authorized ITAM owner"));
        require(active.authorizationStatus() == PartnerAuthorizationStatus.ACTIVE && active.selectableOn(LocalDate.now(CLOCK)),
                "authorized partner is not selectable");

        PartnerPage manufacturerCatalogue = service.search(new PartnerSearchCriteria(
                organization, PartnerRole.MANUFACTURER, PartnerAuthorizationStatus.ACTIVE, "FR", "ISO27001",
                LocalDate.now(CLOCK), null, 50));
        require(manufacturerCatalogue.items().size() == 1, "role-filtered manufacturer catalogue is incorrect");

        Partner suspended = service.suspend(
                active.id(), 3, context(actor, correlation, "suspend-acme-0001", "Suspended after accreditation investigation"));
        require(suspended.authorizationStatus() == PartnerAuthorizationStatus.SUSPENDED && !suspended.selectableOn(LocalDate.now(CLOCK)),
                "suspended partner remains selectable");
        require(events.outboxSnapshot().stream().anyMatch(record -> "itam.partner.created.v1".equals(record.event().eventType().value())),
                "created event missing");
        require(events.outboxSnapshot().stream().anyMatch(record -> "itam.partner.authorized.v1".equals(record.event().eventType().value())),
                "authorized event missing");
        require(events.outboxSnapshot().stream().anyMatch(record -> "itam.partner.suspended.v1".equals(record.event().eventType().value())),
                "suspended event missing");

        service.create(command(organization, subdivision, "VEND-003", "Second Partner SAS", "Second"),
                context(actor, correlation, "create-second-001", "Second governed Partner registration"));
        expect(PartnerQuotaException.class, () -> service.create(
                command(organization, subdivision, "VEND-004", "Third Partner SAS", "Third"),
                context(actor, correlation, "create-third-0001", "Quota boundary verification")));

        PartnerApplicationService disabled = new PartnerApplicationService(
                repository, idempotency, new FeaturePolicy(false, 2), new Governance(organization, subdivision), events, ids, CLOCK);
        expectCode("ITAM_PARTNER_CAPABILITY_UNAVAILABLE", () -> disabled.get(draft.id()));
        expect(IllegalArgumentException.class, () -> new PartnerSearchCriteria(null, null, null, "FRA", null, null, null, 50));

        System.out.println("java-itam-partner-smoke: PASS");
    }

    private static CreatePartnerCommand command(
            DomainIdentifier organization, DomainIdentifier subdivision, String code, String legalName, String displayName) {
        return new CreatePartnerCommand(
                organization,
                subdivision,
                code,
                legalName,
                displayName,
                "FR",
                Set.of("manufacturer", "third_party_support_provider"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 12, 31),
                "https://partner.example.test",
                "https://support.example.test",
                List.of(displayName + " France"),
                List.of(new PartnerExternalId("duns", (code == null ? displayName : code) + "-EXT")),
                List.of(new PartnerAccreditation("ISO27001", "ISO", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "evidence:" + (code == null ? displayName : code))),
                List.of());
    }

    private static PartnerCommandContext context(
            DomainIdentifier actor, DomainIdentifier correlation, String key, String reason) {
        return new PartnerCommandContext(actor, correlation, key, reason);
    }

    private record FeaturePolicy(boolean partnerCatalogueEnabled, long partnerLimit)
            implements io.infranexum.itam.partner.ports.PartnerFeaturePolicy {}

    private record Governance(DomainIdentifier organization, DomainIdentifier subdivision)
            implements io.infranexum.itam.partner.ports.PartnerGovernanceScope {
        @Override public boolean organizationExists(DomainIdentifier organizationId) { return organization.equals(organizationId); }
        @Override public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
            return organization.equals(organizationId) && subdivision.equals(subdivisionId);
        }
    }

    private static final class Idempotency implements PartnerIdempotencyRepository {
        private final Map<String, Record> records = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(records.get(key)); }
        @Override public void insert(Record record) {
            if (records.putIfAbsent(record.key(), record) != null) throw new IllegalStateException("duplicate idempotency key");
        }
    }

    private static final class Repository implements PartnerRepository {
        private final Map<DomainIdentifier, Partner> values = new LinkedHashMap<>();

        @Override public long count() { return values.size(); }
        @Override public boolean existsByCode(DomainIdentifier organizationId, io.infranexum.itam.partner.domain.PartnerCode code) {
            return values.values().stream().anyMatch(value -> value.governingOrganizationId().equals(organizationId) && value.code().equals(code));
        }
        @Override public boolean hasIdentityTokenCollision(DomainIdentifier organizationId, Set<String> tokens) {
            return values.values().stream().filter(value -> value.governingOrganizationId().equals(organizationId))
                    .anyMatch(value -> intersects(value.identityTokens(), tokens));
        }
        @Override public Optional<Partner> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public void insert(Partner partner) {
            if (values.putIfAbsent(partner.id(), partner) != null) throw new IllegalStateException("duplicate partner");
        }
        @Override public void updateLifecycle(Partner partner, long expectedVersion) {
            Partner current = values.get(partner.id());
            if (current == null || current.version() != expectedVersion) throw new PartnerConflictException("VERSION_CONFLICT", "partner version changed");
            values.put(partner.id(), partner);
        }
        @Override public PartnerPage search(PartnerSearchCriteria criteria) {
            List<Partner> filtered = values.values().stream()
                    .filter(value -> criteria.governingOrganizationId() == null || value.governingOrganizationId().equals(criteria.governingOrganizationId()))
                    .filter(value -> criteria.role() == null || value.roles().contains(criteria.role()))
                    .filter(value -> criteria.authorizationStatus() == null || value.authorizationStatus() == criteria.authorizationStatus())
                    .filter(value -> criteria.countryCode() == null || value.countryCode().equals(criteria.countryCode()))
                    .filter(value -> criteria.accreditation() == null || value.accreditations().stream().anyMatch(accreditation -> accreditation.code().equals(criteria.accreditation())))
                    .filter(value -> criteria.effectiveOn() == null || value.selectableOn(criteria.effectiveOn()))
                    .filter(value -> criteria.afterId() == null || value.id().compareTo(criteria.afterId()) > 0)
                    .sorted(Comparator.comparing(Partner::id))
                    .limit((long) criteria.limit() + 1L)
                    .toList();
            DomainIdentifier next = filtered.size() > criteria.limit() ? filtered.get(criteria.limit() - 1).id() : null;
            List<Partner> page = filtered.size() > criteria.limit() ? new ArrayList<>(filtered.subList(0, criteria.limit())) : filtered;
            return new PartnerPage(page, next);
        }
        private static boolean intersects(Set<String> left, Set<String> right) {
            Set<String> copy = new LinkedHashSet<>(left); copy.retainAll(right); return !copy.isEmpty();
        }
    }

    private static void expectCode(String code, ThrowingAction action) {
        try {
            action.run();
        } catch (PartnerConflictException error) {
            require(code.equals(error.code()), "unexpected Partner code: " + error.code());
            return;
        } catch (Exception error) {
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected PartnerConflictException " + code);
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
