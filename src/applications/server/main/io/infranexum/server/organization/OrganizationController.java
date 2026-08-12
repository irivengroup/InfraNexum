package io.infranexum.server.organization;

import static io.infranexum.server.organization.OrganizationApiModels.CreateOrganizationRequest;
import static io.infranexum.server.organization.OrganizationApiModels.CreateScopeRequest;
import static io.infranexum.server.organization.OrganizationApiModels.CreateSubdivisionRequest;
import static io.infranexum.server.organization.OrganizationApiModels.OrganizationResponse;
import static io.infranexum.server.organization.OrganizationApiModels.ScopeResponse;
import static io.infranexum.server.organization.OrganizationApiModels.SubdivisionResponse;
import static io.infranexum.server.organization.OrganizationApiModels.TransitionRequest;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.organization.application.CreateOrganizationCommand;
import io.infranexum.organization.application.CreateSubdivisionCommand;
import io.infranexum.organization.application.CreateTemporalScopeCommand;
import io.infranexum.organization.application.OrganizationApplicationService;
import io.infranexum.organization.application.OrganizationCommandContext;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Local-only pre-IAM HTTP adapter invoking the authoritative Organization application service. */
@RestController
@RequestMapping("/api/v1/iam/organizations")
public final class OrganizationController {
    private static final String LOCAL_ACTOR = "local-development";

    private final OrganizationApplicationService service;
    private final UuidV7Generator ids;
    private final Clock clock;

    public OrganizationController(
            OrganizationApplicationService service, @Qualifier("platformClock") Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = new UuidV7Generator(clock, new SecureRandom());
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(
            @Valid @RequestBody CreateOrganizationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {
        var command = new CreateOrganizationCommand(
                request.code(),
                request.displayName(),
                request.legalName(),
                request.countryCode(),
                request.defaultLanguage(),
                request.timezone(),
                request.currency(),
                parseNullableIdentifier(request.parentOrganizationId()));
        var result = service.createOrganization(
                command, context(servletRequest, idempotencyKey, "local organization bootstrap"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(result.version()))
                .body(OrganizationResponse.from(result));
    }

    @GetMapping
    public List<OrganizationResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        OrganizationState state = status == null || status.isBlank()
                ? null
                : OrganizationState.valueOf(status.strip().toUpperCase(Locale.ROOT));
        return service.searchOrganizations(q, state, offset, limit).stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> get(@PathVariable String id) {
        var organization = service.getOrganization(DomainIdentifier.parse(id));
        return ResponseEntity.ok()
                .eTag(etag(organization.version()))
                .body(OrganizationResponse.from(organization));
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<OrganizationResponse> suspend(
            @PathVariable String id,
            @Valid @RequestBody TransitionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {
        var organization = service.suspend(
                DomainIdentifier.parse(id),
                request.version(),
                context(servletRequest, idempotencyKey, request.reason()));
        return ResponseEntity.ok()
                .eTag(etag(organization.version()))
                .body(OrganizationResponse.from(organization));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<OrganizationResponse> resume(
            @PathVariable String id,
            @Valid @RequestBody TransitionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {
        var organization = service.resume(
                DomainIdentifier.parse(id),
                request.version(),
                context(servletRequest, idempotencyKey, request.reason()));
        return ResponseEntity.ok()
                .eTag(etag(organization.version()))
                .body(OrganizationResponse.from(organization));
    }

    @PostMapping("/{id}/subdivisions")
    public ResponseEntity<SubdivisionResponse> createSubdivision(
            @PathVariable String id,
            @Valid @RequestBody CreateSubdivisionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {
        DomainIdentifier organizationId = DomainIdentifier.parse(id);
        var subdivision = service.createSubdivision(
                new CreateSubdivisionCommand(
                        organizationId,
                        request.code(),
                        request.displayName(),
                        request.description(),
                        request.type(),
                        parseNullableIdentifier(request.parentSubdivisionId())),
                context(servletRequest, idempotencyKey, "local subdivision bootstrap"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(subdivision.version()))
                .body(SubdivisionResponse.from(subdivision));
    }

    @GetMapping("/{id}/subdivisions")
    public List<SubdivisionResponse> subdivisions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listSubdivisions(DomainIdentifier.parse(id), offset, limit).stream()
                .map(SubdivisionResponse::from)
                .toList();
    }

    @PostMapping("/{id}/scopes")
    public ResponseEntity<ScopeResponse> createScope(
            @PathVariable String id,
            @Valid @RequestBody CreateScopeRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {
        DomainIdentifier organizationId = DomainIdentifier.parse(id);
        var scope = service.createTemporalScope(
                new CreateTemporalScopeCommand(
                        organizationId,
                        parseNullableIdentifier(request.subdivisionId()),
                        request.type(),
                        request.validFrom(),
                        request.validTo()),
                context(servletRequest, idempotencyKey, "local temporal scope bootstrap"));
        return ResponseEntity.status(HttpStatus.CREATED).body(ScopeResponse.from(scope));
    }

    @GetMapping("/{id}/scopes/effective")
    public List<ScopeResponse> effectiveScopes(
            @PathVariable String id,
            @RequestParam(required = false) Instant at) {
        Instant effectiveAt = at == null ? clock.instant() : at;
        return service.effectiveScopes(DomainIdentifier.parse(id), effectiveAt).stream()
                .map(ScopeResponse::from)
                .toList();
    }

    private OrganizationCommandContext context(
            HttpServletRequest request, String idempotencyKey, String reason) {
        DomainIdentifier correlationId = CorrelationContext.identifier(request).orElseGet(ids::next);
        return new OrganizationCommandContext(
                LOCAL_ACTOR, correlationId, idempotencyKey, normalizeReason(reason));
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "local administration" : reason;
    }

    private static DomainIdentifier parseNullableIdentifier(String value) {
        return value == null || value.isBlank() ? null : DomainIdentifier.parse(value);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}
