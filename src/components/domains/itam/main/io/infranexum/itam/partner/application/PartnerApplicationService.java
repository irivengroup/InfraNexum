package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.MemorableCodeGenerator;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Application use cases for PGM-07-E01 Partner catalogues and authorization lifecycle. */
public final class PartnerApplicationService {
    private static final ContractVersion EVENT_VERSION = ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE = new EventSource("infranexum.itam.partner");

    private final PartnerRepository partners;
    private final PartnerIdempotencyRepository idempotency;
    private final PartnerFeaturePolicy features;
    private final PartnerGovernanceScope governance;
    private final TransactionalEventStore events;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final MemorableCodeGenerator codes = new MemorableCodeGenerator();

    public PartnerApplicationService(
            PartnerRepository partners, PartnerIdempotencyRepository idempotency, PartnerFeaturePolicy features,
            PartnerGovernanceScope governance, TransactionalEventStore events, UuidV7Generator ids, Clock clock) {
        this.partners = Objects.requireNonNull(partners, "partners");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.features = Objects.requireNonNull(features, "features");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.events = Objects.requireNonNull(events, "events");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Partner create(CreatePartnerCommand command, PartnerCommandContext context) {
        Objects.requireNonNull(command, "command"); Objects.requireNonNull(context, "context");
        requireEnabled();
        Objects.requireNonNull(command.governingOrganizationId(), "governingOrganizationId");
        validateScope(command.governingOrganizationId(), command.governingSubdivisionId());
        String requestedCode = optionalCode(command.code());
        PartnerCode explicitCode = requestedCode == null ? null : new PartnerCode(requestedCode);
        Set<PartnerRole> roles = parseRoles(command.roles());
        String fingerprint = fingerprint("create", command.governingOrganizationId(), command.governingSubdivisionId(),
                explicitCode == null ? "<auto>" : explicitCode.value(), command.legalName(), command.displayName(),
                command.countryCode(), roles, Objects.requireNonNull(command.validFrom(), "validFrom"), command.validUntil(),
                command.officialWebsite(), command.supportPortal(), safeList(command.aliases()), safeList(command.externalIds()),
                safeList(command.accreditations()), safeList(command.contacts()));

        return execute(transaction -> {
            Optional<PartnerIdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) return replay(prior.orElseThrow(), fingerprint, "create");
            DomainIdentifier id = ids.next();
            PartnerCode code = explicitCode == null ? new PartnerCode(codes.generate(command.displayName(), id, 32)) : explicitCode;
            Partner prototype = Partner.draft(id, command.governingOrganizationId(), command.governingSubdivisionId(),
                    code, command.legalName(), command.displayName(), command.countryCode(), roles,
                    command.validFrom(), command.validUntil(), command.officialWebsite(), command.supportPortal(),
                    safeList(command.aliases()), safeList(command.externalIds()), safeList(command.accreditations()),
                    safeList(command.contacts()), context.actorId(), context.reason(), clock.instant());
            if (partners.count() >= features.partnerLimit()) throw new PartnerQuotaException();
            if (partners.existsByCode(prototype.governingOrganizationId(), prototype.code())) {
                throw new PartnerConflictException("PARTNER_CODE_CONFLICT", "partner code already exists in governing organization");
            }
            if (partners.hasIdentityTokenCollision(prototype.governingOrganizationId(), prototype.identityTokens())) {
                throw new PartnerConflictException("PARTNER_DUPLICATE", "potential duplicate partner identity already exists");
            }
            partners.insert(prototype);
            transaction.append(event("itam.partner.created.v1", prototype, context));
            idempotency.insert(new PartnerIdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "create", prototype.id(), prototype.createdAt()));
            return prototype;
        });
    }

    public Partner submitApproval(DomainIdentifier id, long expectedVersion, PartnerCommandContext context) {
        return transition(id, expectedVersion, context, "submit-approval", null,
                partner -> partner.submitApproval(context.actorId(), context.reason(), clock.instant()));
    }

    public Partner authorize(DomainIdentifier id, long expectedVersion, PartnerCommandContext context) {
        return transition(id, expectedVersion, context, "authorize", "itam.partner.authorized.v1",
                partner -> partner.authorize(context.actorId(), context.reason(), clock.instant(), LocalDate.now(clock)));
    }

    public Partner suspend(DomainIdentifier id, long expectedVersion, PartnerCommandContext context) {
        return transition(id, expectedVersion, context, "suspend", "itam.partner.suspended.v1",
                partner -> partner.suspend(context.actorId(), context.reason(), clock.instant()));
    }

    public Partner get(DomainIdentifier id) { requireEnabled(); return requirePartner(id); }

    public PartnerPage search(PartnerSearchCriteria criteria) {
        requireEnabled(); Objects.requireNonNull(criteria, "criteria");
        if (criteria.governingOrganizationId() != null
                && !governance.organizationExists(criteria.governingOrganizationId())) throw new PartnerNotFoundException();
        return partners.search(criteria);
    }

    private Partner transition(
            DomainIdentifier id, long expectedVersion, PartnerCommandContext context, String operation,
            String eventType, Transition transition) {
        requireEnabled(); Objects.requireNonNull(id, "id"); Objects.requireNonNull(context, "context");
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        String fingerprint = fingerprint(operation, id, expectedVersion, context.reason());
        return execute(transaction -> {
            Optional<PartnerIdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) return replay(prior.orElseThrow(), fingerprint, operation);
            Partner current = requirePartner(id);
            if (current.version() != expectedVersion) {
                throw new PartnerConflictException("VERSION_CONFLICT", "partner version changed");
            }
            Partner changed = transition.apply(current);
            partners.updateLifecycle(changed, expectedVersion);
            if (eventType != null) transaction.append(event(eventType, changed, context));
            idempotency.insert(new PartnerIdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, operation, changed.id(), clock.instant()));
            return changed;
        });
    }

    private Partner replay(PartnerIdempotencyRepository.Record prior, String fingerprint, String operation) {
        if (!prior.operation().equals(operation) || !prior.payloadSha256().equals(fingerprint)) {
            throw new PartnerConflictException("IDEMPOTENCY_CONFLICT", "idempotency key was used with another payload");
        }
        return partners.findById(prior.partnerId()).orElseThrow(PartnerNotFoundException::new);
    }

    private Partner requirePartner(DomainIdentifier id) {
        return partners.findById(Objects.requireNonNull(id, "id")).orElseThrow(PartnerNotFoundException::new);
    }

    private void validateScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        if (!governance.organizationExists(organizationId)) {
            throw new PartnerConflictException("GOVERNING_ORGANIZATION_INVALID", "governing organization is unavailable");
        }
        if (subdivisionId != null && !governance.subdivisionExists(organizationId, subdivisionId)) {
            throw new PartnerConflictException("GOVERNING_SUBDIVISION_INVALID", "governing subdivision is unavailable");
        }
    }

    private void requireEnabled() {
        if (!features.partnerCatalogueEnabled()) {
            throw new PartnerConflictException("ITAM_PARTNER_CAPABILITY_UNAVAILABLE", "ITAM Partner catalogue is unavailable");
        }
    }

    private EventEnvelope event(String type, Partner partner, PartnerCommandContext context) {
        String payload = "{"
                + "\"partner_id\":\"" + partner.id() + "\","
                + "\"organization_id\":\"" + partner.governingOrganizationId() + "\","
                + "\"code\":\"" + json(partner.code().value()) + "\","
                + "\"authorization_status\":\"" + partner.authorizationStatus().wireValue() + "\","
                + "\"version\":" + partner.version()
                + "}";
        return new EventEnvelope(ids.next(), new EventType(type), EVENT_VERSION, clock.instant(), SOURCE,
                context.correlationId(), partner.id(), payload);
    }

    private <T> T execute(io.infranexum.core.events.TransactionalWork<T> work) {
        try { return events.execute(work).value(); }
        catch (TransactionExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof PartnerConflictException conflict) throw conflict;
            if (cause instanceof PartnerNotFoundException notFound) throw notFound;
            if (cause instanceof PartnerQuotaException quota) throw quota;
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            throw failure;
        }
    }

    private static String optionalCode(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Set<PartnerRole> parseRoles(Set<String> values) {
        Objects.requireNonNull(values, "roles"); LinkedHashSet<PartnerRole> result = new LinkedHashSet<>();
        for (String value : values) result.add(PartnerRole.parse(value));
        if (result.isEmpty()) throw new IllegalArgumentException("partner requires at least one role");
        return Set.copyOf(result);
    }

    private static <T> java.util.List<T> safeList(java.util.List<T> value) {
        return value == null ? java.util.List.of() : java.util.List.copyOf(value);
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

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @FunctionalInterface private interface Transition { Partner apply(Partner partner); }
}
