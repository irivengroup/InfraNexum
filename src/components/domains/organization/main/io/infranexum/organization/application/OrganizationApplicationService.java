package io.infranexum.organization.application;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationNotFoundException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.ScopeType;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.domain.TemporalScope;
import io.infranexum.organization.ports.IdempotencyRepository;
import io.infranexum.organization.ports.OrganizationFeaturePolicy;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import io.infranexum.organization.ports.TemporalScopeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Application use cases for the authoritative Organization bounded context foundation. */
public final class OrganizationApplicationService {
    private static final ContractVersion EVENT_VERSION = ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE = new EventSource("infranexum.organization");

    private final OrganizationRepository organizations;
    private final SubdivisionRepository subdivisions;
    private final TemporalScopeRepository scopes;
    private final IdempotencyRepository idempotency;
    private final OrganizationFeaturePolicy features;
    private final TransactionalEventStore events;
    private final UuidV7Generator ids;
    private final Clock clock;

    public OrganizationApplicationService(
            OrganizationRepository organizations,
            SubdivisionRepository subdivisions,
            TemporalScopeRepository scopes,
            IdempotencyRepository idempotency,
            OrganizationFeaturePolicy features,
            TransactionalEventStore events,
            UuidV7Generator ids,
            Clock clock) {
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.subdivisions = Objects.requireNonNull(subdivisions, "subdivisions");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.features = Objects.requireNonNull(features, "features");
        this.events = Objects.requireNonNull(events, "events");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Organization createOrganization(
            CreateOrganizationCommand command, OrganizationCommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        OrganizationCode code = new OrganizationCode(command.code());
        String fingerprint = fingerprint(
                "create-organization",
                code.value(),
                command.displayName(),
                command.legalName(),
                command.countryCode(),
                command.defaultLanguage(),
                command.timezone(),
                command.currency(),
                command.parentOrganizationId());

        return execute(transaction -> {
            Optional<IdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) {
                return repeatedOrganization(prior.orElseThrow(), fingerprint);
            }

            if (organizations.count() >= features.organizationLimit()) {
                throw new OrganizationQuotaException("organization.organizations.max");
            }
            if (organizations.existsByCode(code)) {
                throw new OrganizationConflictException(
                        "ORG_CODE_CONFLICT", "organization code already exists");
            }
            if (command.parentOrganizationId() != null) {
                if (!features.supportsOrganizationHierarchy()) {
                    throw new OrganizationConflictException(
                            "ORG_HIERARCHY_UNAVAILABLE",
                            "organization hierarchy is unavailable for the active profile");
                }
                requireOrganization(command.parentOrganizationId());
            }

            Instant now = clock.instant();
            Organization provisional = Organization.provisioning(
                    ids.next(),
                    code,
                    command.displayName(),
                    command.legalName(),
                    command.countryCode(),
                    command.defaultLanguage(),
                    command.timezone(),
                    command.currency(),
                    command.parentOrganizationId(),
                    now);
            organizations.insert(provisional);

            Organization active = provisional.activate(now);
            organizations.update(active, provisional.version());
            transaction.append(event(
                    "organization.lifecycle.activated.v1",
                    active.id(),
                    context.correlationId(),
                    now,
                    organizationPayload(active)));
            idempotency.insert(new IdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "organization", active.id(), now));
            return active;
        });
    }

    public Subdivision createSubdivision(
            CreateSubdivisionCommand command, OrganizationCommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command.organizationId(), "organizationId");
        if (!features.supportsSubdivisions()) {
            throw new OrganizationConflictException(
                    "SUBDIVISIONS_UNAVAILABLE",
                    "subdivisions are unavailable for the active profile");
        }

        SubdivisionCode code = new SubdivisionCode(command.code());
        SubdivisionType type = SubdivisionType.parse(command.type());
        String fingerprint = fingerprint(
                "create-subdivision",
                command.organizationId(),
                code.value(),
                command.displayName(),
                command.description(),
                type.name(),
                command.parentSubdivisionId());

        return execute(transaction -> {
            Optional<IdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) {
                return repeatedSubdivision(
                        prior.orElseThrow(), fingerprint, command.organizationId());
            }

            Organization owner = requireOrganization(command.organizationId());
            if (owner.state() != OrganizationState.ACTIVE) {
                throw new OrganizationConflictException(
                        "ORG_NOT_ACTIVE", "organization must be active");
            }
            if (subdivisions.countByOrganization(command.organizationId())
                    >= features.subdivisionLimit()) {
                throw new OrganizationQuotaException("organization.subdivisions.max");
            }
            if (subdivisions.existsCode(command.organizationId(), code)) {
                throw new OrganizationConflictException(
                        "SUBDIVISION_CODE_CONFLICT",
                        "subdivision code already exists in organization");
            }
            requireSubdivisionDepth(command.organizationId(), command.parentSubdivisionId());

            Instant now = clock.instant();
            Subdivision subdivision = Subdivision.active(
                    ids.next(),
                    command.organizationId(),
                    code,
                    command.displayName(),
                    command.description(),
                    type,
                    command.parentSubdivisionId(),
                    now);
            subdivisions.insert(subdivision);
            transaction.append(event(
                    "organization.subdivision.created.v1",
                    subdivision.id(),
                    context.correlationId(),
                    now,
                    subdivisionPayload(subdivision)));
            idempotency.insert(new IdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "subdivision", subdivision.id(), now));
            return subdivision;
        });
    }

    public TemporalScope createTemporalScope(
            CreateTemporalScopeCommand command, OrganizationCommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command.organizationId(), "organizationId");
        Objects.requireNonNull(command.validFrom(), "validFrom");
        ScopeType type = ScopeType.parse(command.type());
        String fingerprint = fingerprint(
                "create-temporal-scope",
                command.organizationId(),
                command.subdivisionId(),
                type.name(),
                command.validFrom(),
                command.validTo());

        return execute(transaction -> {
            Optional<IdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) {
                return repeatedScope(prior.orElseThrow(), fingerprint, command.organizationId());
            }

            Organization owner = requireOrganization(command.organizationId());
            if (owner.state() != OrganizationState.ACTIVE) {
                throw new OrganizationConflictException(
                        "ORG_NOT_ACTIVE", "organization must be active");
            }
            if (command.subdivisionId() != null) {
                subdivisions.findById(command.organizationId(), command.subdivisionId())
                        .orElseThrow(OrganizationNotFoundException::new);
            }

            Instant now = clock.instant();
            TemporalScope scope = new TemporalScope(
                    ids.next(),
                    command.organizationId(),
                    command.subdivisionId(),
                    type,
                    command.validFrom(),
                    command.validTo(),
                    0,
                    now);
            scopes.insert(scope);
            transaction.append(event(
                    "organization.scope.created.v1",
                    scope.id(),
                    context.correlationId(),
                    now,
                    scopePayload(scope)));
            idempotency.insert(new IdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "scope", scope.id(), now));
            return scope;
        });
    }

    public Organization getOrganization(DomainIdentifier id) {
        return requireOrganization(id);
    }

    public List<Organization> searchOrganizations(
            String query, OrganizationState state, int offset, int limit) {
        requirePage(offset, limit);
        return organizations.search(query, state, offset, limit);
    }

    public List<Subdivision> listSubdivisions(
            DomainIdentifier organizationId, int offset, int limit) {
        requirePage(offset, limit);
        requireOrganization(organizationId);
        return subdivisions.list(organizationId, offset, limit);
    }

    public List<TemporalScope> effectiveScopes(
            DomainIdentifier organizationId, Instant at) {
        requireOrganization(organizationId);
        return scopes.effective(organizationId, Objects.requireNonNull(at, "at"));
    }

    public Organization suspend(
            DomainIdentifier id, long expectedVersion, OrganizationCommandContext context) {
        return transition(
                id,
                expectedVersion,
                context,
                "organization.lifecycle.suspended.v1",
                Organization::suspend);
    }

    public Organization resume(
            DomainIdentifier id, long expectedVersion, OrganizationCommandContext context) {
        return transition(
                id,
                expectedVersion,
                context,
                "organization.lifecycle.reactivated.v1",
                Organization::resume);
    }

    private Organization transition(
            DomainIdentifier id,
            long expectedVersion,
            OrganizationCommandContext context,
            String eventType,
            Transition transition) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String fingerprint = fingerprint(
                "transition-organization",
                id,
                expectedVersion,
                eventType,
                context.reason());

        return execute(transaction -> {
            Optional<IdempotencyRepository.Record> prior = idempotency.find(context.idempotencyKey());
            if (prior.isPresent()) {
                return repeatedOrganizationTransition(prior.orElseThrow(), fingerprint, id);
            }

            Organization current = requireOrganization(id);
            if (current.version() != expectedVersion) {
                throw new OrganizationConflictException(
                        "VERSION_CONFLICT", "organization version changed");
            }
            Instant now = clock.instant();
            Organization changed = transition.apply(current, now);
            organizations.update(changed, expectedVersion);
            transaction.append(event(
                    eventType,
                    changed.id(),
                    context.correlationId(),
                    now,
                    organizationPayload(changed)));
            idempotency.insert(new IdempotencyRepository.Record(
                    context.idempotencyKey(), fingerprint, "organization-transition", changed.id(), now));
            return changed;
        });
    }

    private <T> T execute(TransactionalWork<T> work) {
        try {
            return events.execute(work).value();
        } catch (TransactionExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof OrganizationConflictException conflict) {
                throw conflict;
            }
            if (cause instanceof OrganizationNotFoundException notFound) {
                throw notFound;
            }
            if (cause instanceof OrganizationQuotaException quota) {
                throw quota;
            }
            if (cause instanceof io.infranexum.organization.domain.OrganizationStateException state) {
                throw state;
            }
            if (cause instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw failure;
        }
    }

    private Organization requireOrganization(DomainIdentifier id) {
        return organizations.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private void requireSubdivisionDepth(
            DomainIdentifier organizationId, DomainIdentifier parentSubdivisionId) {
        if (parentSubdivisionId == null) {
            if (features.hierarchyDepthLimit() < 1) {
                throw new OrganizationQuotaException("organization.hierarchy_depth.max");
            }
            return;
        }

        long depth = 1;
        Set<DomainIdentifier> seen = new HashSet<>();
        DomainIdentifier cursor = parentSubdivisionId;
        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw new OrganizationConflictException(
                        "SUBDIVISION_CYCLE", "subdivision hierarchy contains a cycle");
            }
            Subdivision node = subdivisions.findById(organizationId, cursor)
                    .orElseThrow(OrganizationNotFoundException::new);
            depth = Math.addExact(depth, 1L);
            if (depth > features.hierarchyDepthLimit()) {
                throw new OrganizationQuotaException("organization.hierarchy_depth.max");
            }
            cursor = node.parentSubdivisionId();
        }
    }

    private Organization repeatedOrganization(
            IdempotencyRepository.Record prior, String fingerprint) {
        requireReplay(prior, fingerprint, "organization");
        return organizations.findById(prior.resourceId())
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private Organization repeatedOrganizationTransition(
            IdempotencyRepository.Record prior, String fingerprint, DomainIdentifier expectedId) {
        requireReplay(prior, fingerprint, "organization-transition");
        if (!prior.resourceId().equals(expectedId)) {
            throw idempotencyConflict();
        }
        return organizations.findById(prior.resourceId())
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private Subdivision repeatedSubdivision(
            IdempotencyRepository.Record prior,
            String fingerprint,
            DomainIdentifier organizationId) {
        requireReplay(prior, fingerprint, "subdivision");
        return subdivisions.findById(organizationId, prior.resourceId())
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private TemporalScope repeatedScope(
            IdempotencyRepository.Record prior,
            String fingerprint,
            DomainIdentifier organizationId) {
        requireReplay(prior, fingerprint, "scope");
        return scopes.findById(organizationId, prior.resourceId())
                .orElseThrow(OrganizationNotFoundException::new);
    }

    private static void requireReplay(
            IdempotencyRepository.Record prior, String fingerprint, String resourceType) {
        if (!prior.resourceType().equals(resourceType)
                || !prior.payloadSha256().equals(fingerprint)) {
            throw idempotencyConflict();
        }
    }

    private static OrganizationConflictException idempotencyConflict() {
        return new OrganizationConflictException(
                "IDEMPOTENCY_CONFLICT",
                "idempotency key was already used with another payload");
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    private EventEnvelope event(
            String type,
            DomainIdentifier aggregateId,
            DomainIdentifier correlationId,
            Instant occurredAt,
            String payload) {
        return new EventEnvelope(
                ids.next(),
                new EventType(type),
                EVENT_VERSION,
                occurredAt,
                SOURCE,
                correlationId,
                aggregateId,
                payload);
    }

    private static String organizationPayload(Organization organization) {
        return "{"
                + "\"organization_id\":\"" + organization.id() + "\","
                + "\"code\":\"" + json(organization.code().value()) + "\","
                + "\"state\":\"" + organization.state().name().toLowerCase(Locale.ROOT) + "\","
                + "\"version\":" + organization.version()
                + "}";
    }

    private static String subdivisionPayload(Subdivision subdivision) {
        return "{"
                + "\"organization_id\":\"" + subdivision.organizationId() + "\","
                + "\"subdivision_id\":\"" + subdivision.id() + "\","
                + "\"code\":\"" + json(subdivision.code().value()) + "\","
                + "\"type\":\"" + subdivision.type().wireValue() + "\","
                + "\"version\":" + subdivision.version()
                + "}";
    }

    private static String scopePayload(TemporalScope scope) {
        return "{"
                + "\"organization_id\":\"" + scope.organizationId() + "\","
                + "\"scope_id\":\"" + scope.id() + "\","
                + "\"scope_type\":\"" + scope.type().wireValue() + "\","
                + "\"valid_from\":\"" + scope.validFrom() + "\""
                + "}";
    }

    private static String json(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "<null>" : value.toString();
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @FunctionalInterface
    private interface Transition {
        Organization apply(Organization organization, Instant now);
    }
}
