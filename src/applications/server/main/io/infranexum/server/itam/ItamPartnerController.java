package io.infranexum.server.itam;

import static io.infranexum.server.itam.ItamPartnerApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.itam.partner.application.CreatePartnerCommand;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.identityaccess.ScopedAuthorizationGuard;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

/** HTTP boundary for the single governed Partner aggregate and its role-filtered catalogues. */
@RestController
@ConditionalOnProperty(name = "infranexum.itam.partner-api-enabled", havingValue = "true")
@RequestMapping("/api/v1/itam/partners")
public final class ItamPartnerController {
    private final PartnerApplicationService service;
    private final ScopedAuthorizationGuard authorization;
    private final UuidV7Generator ids;

    public ItamPartnerController(
            PartnerApplicationService service,
            ScopedAuthorizationGuard authorization,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.ids = Objects.requireNonNull(identifiers, "identifiers");
    }

    @GetMapping
    PartnerPageResponse search(
            @RequestParam(name = "organization_id", required = false) String organizationId,
            @RequestParam(required = false) String role,
            @RequestParam(name = "authorization_status", required = false) String authorizationStatus,
            @RequestParam(name = "country_code", required = false) String countryCode,
            @RequestParam(required = false) String accreditation,
            @RequestParam(name = "effective_on", required = false) LocalDate effectiveOn,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request,
            HttpServletResponse response) {
        DomainIdentifier organization = nullableId(organizationId);
        AuthorizationScope scope = organization == null ? AuthorizationScope.platform() : AuthorizationScope.organization(organization);
        authorization.require(request, response, PermissionCodes.ITAM_PARTNER_READ, scope, "itam-partner", "collection");
        var page = service.search(new PartnerSearchCriteria(
                organization,
                role == null || role.isBlank() ? null : PartnerRole.parse(role),
                authorizationStatus == null || authorizationStatus.isBlank() ? null : PartnerAuthorizationStatus.parse(authorizationStatus),
                countryCode, accreditation, effectiveOn, nullableId(cursor), limit));
        return new PartnerPageResponse(
                page.items().stream().map(PartnerResponse::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString());
    }

    @PostMapping
    ResponseEntity<PartnerResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePartnerRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        DomainIdentifier organization = id(body.governingOrganizationId());
        authorization.require(request, response, PermissionCodes.ITAM_PARTNER_CREATE,
                AuthorizationScope.organization(organization), "itam-partner", "collection");
        Partner partner = service.create(new CreatePartnerCommand(
                organization, nullableId(body.governingSubdivisionId()), body.code(), body.legalName(), body.displayName(),
                body.countryCode(), body.roles(), body.validFrom(), body.validUntil(), body.officialWebsite(), body.supportPortal(),
                safe(body.aliases()), safe(body.externalIds()).stream().map(ExternalIdRequest::toDomain).toList(),
                safe(body.accreditations()).stream().map(AccreditationRequest::toDomain).toList(),
                safe(body.contacts()).stream().map(ContactRequest::toDomain).toList()),
                context(request, idempotencyKey, body.reason()));
        return partnerResponse(partner, HttpStatus.CREATED);
    }

    @PostMapping("/{partnerId}/submit-approval")
    ResponseEntity<PartnerResponse> submitApproval(
            @PathVariable String partnerId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PartnerTransitionRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Partner current = scopedCurrent(partnerId, PermissionCodes.ITAM_PARTNER_APPROVE, request, response);
        return partnerResponse(service.submitApproval(current.id(), version(ifMatch), context(request, idempotencyKey, body.reason())), HttpStatus.OK);
    }

    @PostMapping("/{partnerId}/authorize")
    ResponseEntity<PartnerResponse> authorize(
            @PathVariable String partnerId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PartnerTransitionRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Partner current = scopedCurrent(partnerId, PermissionCodes.ITAM_PARTNER_APPROVE, request, response);
        return partnerResponse(service.authorize(current.id(), version(ifMatch), context(request, idempotencyKey, body.reason())), HttpStatus.OK);
    }

    @PostMapping("/{partnerId}/suspend")
    ResponseEntity<PartnerResponse> suspend(
            @PathVariable String partnerId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PartnerTransitionRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Partner current = scopedCurrent(partnerId, PermissionCodes.ITAM_PARTNER_SUSPEND, request, response);
        return partnerResponse(service.suspend(current.id(), version(ifMatch), context(request, idempotencyKey, body.reason())), HttpStatus.OK);
    }

    private Partner scopedCurrent(
            String partnerId, String permission, HttpServletRequest request, HttpServletResponse response) {
        Partner current = service.get(id(partnerId));
        authorization.require(request, response, permission, AuthorizationScope.organization(current.governingOrganizationId()),
                "itam-partner", current.id().toString());
        return current;
    }

    private PartnerCommandContext context(HttpServletRequest request, String idempotencyKey, String reason) {
        Object actor = request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
        if (!(actor instanceof DomainIdentifier actorId)) {
            throw new IllegalStateException("authenticated actor missing after RBAC boundary");
        }
        return new PartnerCommandContext(actorId, CorrelationContext.identifier(request).orElseGet(ids::next), idempotencyKey, reason);
    }

    private static ResponseEntity<PartnerResponse> partnerResponse(Partner partner, HttpStatus status) {
        return ResponseEntity.status(status).eTag(etag(partner.version())).body(PartnerResponse.from(partner));
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        String normalized = value.strip();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2).strip();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("ver-")) normalized = normalized.substring(4);
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 1) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("If-Match must contain a positive Partner version", failure);
        }
    }

    private static String etag(long version) { return "\"ver-" + version + "\""; }
    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(value); }
    private static DomainIdentifier nullableId(String value) { return value == null || value.isBlank() ? null : id(value); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
}
