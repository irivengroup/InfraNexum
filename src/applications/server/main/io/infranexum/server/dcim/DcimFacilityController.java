package io.infranexum.server.dcim;

import io.infranexum.server.http.AuthenticatedActorContext;
import static io.infranexum.server.dcim.DcimFacilityApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.dcim.facility.application.CreateFacilityCommand;
import io.infranexum.dcim.facility.application.FacilityApplicationService;
import io.infranexum.dcim.facility.application.FacilityCommandContext;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.application.UpdateFacilityCommand;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.server.identityaccess.ScopedAuthorizationGuard;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for sites, buildings, floors, rooms and technical zones. */
@RestController
@ConditionalOnProperty(name = "infranexum.dcim.facility-api-enabled", havingValue = "true")
@RequestMapping("/api/v1/dcim")
public final class DcimFacilityController {
    private final FacilityApplicationService service;
    private final ScopedAuthorizationGuard authorization;
    private final UuidV7Generator ids;

    public DcimFacilityController(
            FacilityApplicationService service,
            ScopedAuthorizationGuard authorization,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.ids = Objects.requireNonNull(identifiers, "identifiers");
    }

    @GetMapping("/{resource}")
    FacilityPageResponse search(
            @PathVariable String resource,
            @RequestParam(name = "organization_id") String organizationId,
            @RequestParam(name = "subdivision_id", required = false) String subdivisionId,
            @RequestParam(name = "parent_id", required = false) String parentId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "country_code", required = false) String countryCode,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request,
            HttpServletResponse response) {
        FacilityKind kind = resourceKind(resource);
        DomainIdentifier organization = id(organizationId);
        authorization.require(request, response, readPermission(kind), AuthorizationScope.organization(organization),
                "dcim-" + kind.wireValue(), "collection");
        var page = service.search(new FacilitySearchCriteria(
                organization, nullableId(subdivisionId), kind, nullableId(parentId), nullableStatus(status), countryCode, nullableId(cursor), limit));
        return new FacilityPageResponse(
                page.items().stream().map(FacilityResponse::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString());
    }

    @GetMapping("/{resource}/{facilityId}")
    ResponseEntity<FacilityResponse> get(
            @PathVariable String resource,
            @PathVariable String facilityId,
            HttpServletRequest request,
            HttpServletResponse response) {
        FacilityKind kind = resourceKind(resource);
        FacilityNode node = service.get(id(facilityId));
        authorization.require(request, response, readPermission(kind), AuthorizationScope.organization(node.organizationId()),
                "dcim-" + kind.wireValue(), node.id().toString());
        requireKind(kind, node);
        return facilityResponse(node, HttpStatus.OK);
    }

    @PostMapping("/{resource}")
    ResponseEntity<FacilityResponse> create(
            @PathVariable String resource,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateFacilityRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        FacilityKind kind = resourceKind(resource);
        DomainIdentifier organization = id(body.organizationId());
        authorization.require(request, response, createPermission(kind), AuthorizationScope.organization(organization),
                "dcim-" + kind.wireValue(), "collection");
        FacilityNode node = service.create(new CreateFacilityCommand(
                kind, organization, id(body.subdivisionId()), nullableId(body.parentId()), body.code(), body.displayName(),
                body.addressLine1(), body.addressLine2(), body.postalCode(), body.city(), body.countryCode(), body.timezone(), body.latitude(), body.longitude(), body.floorCount(), body.levelNumber(),
                body.areaM2(), body.levelHeightM(), body.capacityKw(), body.accessRestriction(), body.zoneType(), body.description()),
                context(request, idempotencyKey, body.reason()));
        return facilityResponse(node, HttpStatus.CREATED);
    }

    @PatchMapping("/{resource}/{facilityId}")
    ResponseEntity<FacilityResponse> update(
            @PathVariable String resource,
            @PathVariable String facilityId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody UpdateFacilityRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        FacilityKind kind = resourceKind(resource);
        FacilityNode current = scopedCurrent(kind, facilityId, updatePermission(kind), request, response);
        FacilityNode node = service.update(current.id(), version(ifMatch), new UpdateFacilityCommand(
                body.displayName(), body.addressLine1(), body.addressLine2(), body.postalCode(), body.city(), body.countryCode(), body.timezone(), body.latitude(), body.longitude(), body.floorCount(),
                body.levelNumber(), body.areaM2(), body.levelHeightM(), body.capacityKw(), body.accessRestriction(),
                body.zoneType(), body.description()), context(request, idempotencyKey, body.reason()));
        return facilityResponse(node, HttpStatus.OK);
    }

    @PostMapping("/{resource}/{facilityId}/status")
    ResponseEntity<FacilityResponse> changeStatus(
            @PathVariable String resource,
            @PathVariable String facilityId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FacilityStatusRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        FacilityKind kind = resourceKind(resource);
        FacilityStatus target = FacilityStatus.parse(body.targetStatus());
        FacilityNode current = scopedCurrent(kind, facilityId, statusPermission(kind, target), request, response);
        FacilityNode node = service.changeStatus(current.id(), version(ifMatch), target,
                context(request, idempotencyKey, body.reason()));
        return facilityResponse(node, HttpStatus.OK);
    }

    private FacilityNode scopedCurrent(
            FacilityKind kind, String facilityId, String permission, HttpServletRequest request, HttpServletResponse response) {
        FacilityNode current = service.get(id(facilityId));
        authorization.require(request, response, permission, AuthorizationScope.organization(current.organizationId()),
                "dcim-" + kind.wireValue(), current.id().toString());
        requireKind(kind, current);
        return current;
    }

    private FacilityCommandContext context(HttpServletRequest request, String idempotencyKey, String reason) {
        Object actor = request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if (!(actor instanceof DomainIdentifier actorId)) {
            throw new IllegalStateException("authenticated actor missing after RBAC boundary");
        }
        return new FacilityCommandContext(
                actorId, CorrelationContext.identifier(request).orElseGet(ids::next), idempotencyKey, reason);
    }

    private static FacilityKind resourceKind(String resource) {
        return switch (Objects.requireNonNull(resource, "resource").strip().toLowerCase(Locale.ROOT)) {
            case "sites" -> FacilityKind.SITE;
            case "buildings" -> FacilityKind.BUILDING;
            case "floors" -> FacilityKind.FLOOR;
            case "rooms" -> FacilityKind.ROOM;
            case "zones" -> FacilityKind.ZONE;
            default -> throw new IllegalArgumentException("unknown DCIM facility resource");
        };
    }

    private static String readPermission(FacilityKind kind) {
        return switch (kind) {
            case SITE -> PermissionCodes.DCIM_SITE_READ;
            case BUILDING -> PermissionCodes.DCIM_BUILDING_READ;
            case FLOOR -> PermissionCodes.DCIM_FLOOR_READ;
            case ROOM -> PermissionCodes.DCIM_ROOM_READ;
            case ZONE -> PermissionCodes.DCIM_ZONE_READ;
        };
    }

    private static String createPermission(FacilityKind kind) {
        return switch (kind) {
            case SITE -> PermissionCodes.DCIM_SITE_CREATE;
            case BUILDING -> PermissionCodes.DCIM_BUILDING_CREATE;
            case FLOOR -> PermissionCodes.DCIM_FLOOR_CREATE;
            case ROOM -> PermissionCodes.DCIM_ROOM_CREATE;
            case ZONE -> PermissionCodes.DCIM_ZONE_CREATE;
        };
    }

    private static String updatePermission(FacilityKind kind) {
        return switch (kind) {
            case SITE -> PermissionCodes.DCIM_SITE_UPDATE;
            case BUILDING -> PermissionCodes.DCIM_BUILDING_UPDATE;
            case FLOOR -> PermissionCodes.DCIM_FLOOR_UPDATE;
            case ROOM -> PermissionCodes.DCIM_ROOM_UPDATE;
            case ZONE -> PermissionCodes.DCIM_ZONE_UPDATE;
        };
    }

    private static String statusPermission(FacilityKind kind, FacilityStatus target) {
        if (target == FacilityStatus.DELETED) {
            return switch (kind) {
                case SITE -> PermissionCodes.DCIM_SITE_DELETE;
                case BUILDING -> PermissionCodes.DCIM_BUILDING_DELETE;
                case FLOOR -> PermissionCodes.DCIM_FLOOR_DELETE;
                case ROOM -> PermissionCodes.DCIM_ROOM_DELETE;
                case ZONE -> PermissionCodes.DCIM_ZONE_DELETE;
            };
        }
        if (target == FacilityStatus.ARCHIVED) {
            return switch (kind) {
                case SITE -> PermissionCodes.DCIM_SITE_ARCHIVE;
                case BUILDING -> PermissionCodes.DCIM_BUILDING_ARCHIVE;
                case FLOOR -> PermissionCodes.DCIM_FLOOR_ARCHIVE;
                case ROOM -> PermissionCodes.DCIM_ROOM_ARCHIVE;
                case ZONE -> PermissionCodes.DCIM_ZONE_UPDATE;
            };
        }
        if (kind == FacilityKind.ROOM && target == FacilityStatus.LOCKED) return PermissionCodes.DCIM_ROOM_LOCK;
        return updatePermission(kind);
    }

    private static void requireKind(FacilityKind expected, FacilityNode node) {
        if (node.kind() != expected) throw new IllegalArgumentException("facility identifier does not belong to requested resource");
    }

    private static ResponseEntity<FacilityResponse> facilityResponse(FacilityNode node, HttpStatus status) {
        return ResponseEntity.status(status).eTag(etag(node.version())).body(FacilityResponse.from(node));
    }

    private static String etag(long version) { return "\"ver-" + version + "\""; }

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
            throw new IllegalArgumentException("If-Match must contain a positive facility version", failure);
        }
    }

    private static FacilityStatus nullableStatus(String value) {
        return value == null || value.isBlank() ? null : FacilityStatus.parse(value);
    }

    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(value); }
    private static DomainIdentifier nullableId(String value) { return value == null || value.isBlank() ? null : id(value); }
}
