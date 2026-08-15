package io.infranexum.server.itam;

import static io.infranexum.server.itam.ItamAssetApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.asset.application.AssetCommandContext;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.application.CreateAssetCommand;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.server.identity.LocalAuthenticationFilter;
import io.infranexum.server.identityaccess.ScopedAuthorizationGuard;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
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

/** Organization-scoped HTTP boundary for the PGM-07-E02 ITAM asset lifecycle. */
@RestController
@ConditionalOnProperty(name = "infranexum.itam.asset-api-enabled", havingValue = "true")
@RequestMapping("/api/v1/itam/assets")
public final class ItamAssetController {
    private final AssetApplicationService service;
    private final ScopedAuthorizationGuard authorization;
    private final UuidV7Generator ids;

    public ItamAssetController(
            AssetApplicationService service, ScopedAuthorizationGuard authorization,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.ids = Objects.requireNonNull(identifiers, "identifiers");
    }

    @GetMapping
    AssetPageResponse search(
            @RequestParam(name = "organization_id", required = false) String organizationId,
            @RequestParam(name = "asset_type", required = false) String assetType,
            @RequestParam(name = "lifecycle_status", required = false) String lifecycleStatus,
            @RequestParam(name = "rsot_object_id", required = false) String rsotObjectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        DomainIdentifier organization = nullableId(organizationId);
        AuthorizationScope scope = organization == null ? AuthorizationScope.platform() : AuthorizationScope.organization(organization);
        authorization.require(request, response, PermissionCodes.ITAM_ASSET_READ, scope, "itam-asset", "collection");
        var page = service.search(new AssetSearchCriteria(
                organization, nullableType(assetType), nullableStatus(lifecycleStatus), nullableId(rsotObjectId), nullableId(cursor), limit));
        return new AssetPageResponse(page.items().stream().map(AssetResponse::from).toList(), text(page.nextAfterId()));
    }

    @GetMapping("/{assetId}")
    ResponseEntity<AssetResponse> get(
            @PathVariable String assetId, HttpServletRequest request, HttpServletResponse response) {
        Asset asset = scopedCurrent(assetId, PermissionCodes.ITAM_ASSET_READ, request, response);
        return assetResponse(asset, HttpStatus.OK);
    }

    @GetMapping("/{assetId}/custody")
    List<CustodyEventResponse> custody(
            @PathVariable String assetId,
            @RequestParam(name = "after_sequence", defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        Asset asset = scopedCurrent(assetId, PermissionCodes.ITAM_ASSET_READ, request, response);
        return service.custodyHistory(asset.id(), afterSequence, limit).stream().map(CustodyEventResponse::from).toList();
    }

    @PostMapping
    ResponseEntity<AssetResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateAssetRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        DomainIdentifier organization = id(body.owningOrganizationId());
        authorization.require(request, response, PermissionCodes.ITAM_ASSET_CREATE,
                AuthorizationScope.organization(organization), "itam-asset", "collection");
        Asset asset = service.create(new CreateAssetCommand(
                        id(body.rsotObjectId()), body.assetType(), organization, nullableId(body.owningSubdivisionId()),
                        body.acquisitionDate(), body.acquisitionValue(), body.currencyCode(), nullableId(body.acquiredFromPartnerId()),
                        nullableId(body.producerPartnerId())),
                context(request, idempotencyKey, body.reason(), null));
        return assetResponse(asset, HttpStatus.CREATED);
    }

    @PostMapping("/{assetId}/producer")
    ResponseEntity<AssetResponse> setProducer(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SetAssetProducerRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Asset current = scopedCurrent(assetId, PermissionCodes.ITAM_ASSET_UPDATE, request, response);
        Asset changed = service.setProducer(current.id(), version(ifMatch), id(body.producerPartnerId()),
                context(request, idempotencyKey, body.reason(), null));
        return assetResponse(changed, HttpStatus.OK);
    }

    @PostMapping("/{assetId}/receive")
    ResponseEntity<AssetResponse> receive(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.receive(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/stock")
    ResponseEntity<AssetResponse> stock(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.stock(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/assign")
    ResponseEntity<AssetResponse> assign(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.assign(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/deploy")
    ResponseEntity<AssetResponse> deploy(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.deploy(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/transfer")
    ResponseEntity<AssetResponse> transfer(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.transfer(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/maintenance/start")
    ResponseEntity<AssetResponse> startMaintenance(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.startMaintenance(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/maintenance/return")
    ResponseEntity<AssetResponse> returnFromMaintenance(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.returnFromMaintenance(asset.id(), version(ifMatch), custodian(body), context));
    }

    @PostMapping("/{assetId}/retire")
    ResponseEntity<AssetResponse> retire(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.retire(asset.id(), version(ifMatch), context));
    }

    @PostMapping("/{assetId}/dispose")
    ResponseEntity<AssetResponse> dispose(
            @PathVariable String assetId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return transition(assetId, ifMatch, idempotencyKey, body, request, response,
                (asset, context) -> service.dispose(asset.id(), version(ifMatch), context));
    }

    private ResponseEntity<AssetResponse> transition(
            String assetId, String ifMatch, String idempotencyKey, AssetTransitionRequest body,
            HttpServletRequest request, HttpServletResponse response, BiFunction<Asset, AssetCommandContext, Asset> operation) {
        Asset current = scopedCurrent(assetId, PermissionCodes.ITAM_ASSET_UPDATE, request, response);
        Asset changed = operation.apply(current, context(request, idempotencyKey, body.reason(), body.evidenceReference()));
        return assetResponse(changed, HttpStatus.OK);
    }

    private Asset scopedCurrent(
            String assetId, String permission, HttpServletRequest request, HttpServletResponse response) {
        Asset current = service.get(id(assetId));
        authorization.require(request, response, permission, AuthorizationScope.organization(current.owningOrganizationId()),
                "itam-asset", current.id().toString());
        return current;
    }

    private AssetCommandContext context(
            HttpServletRequest request, String idempotencyKey, String reason, String evidenceReference) {
        Object actor = request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE);
        if (!(actor instanceof DomainIdentifier actorId)) {
            throw new IllegalStateException("authenticated actor missing after RBAC boundary");
        }
        return new AssetCommandContext(actorId, CorrelationContext.identifier(request).orElseGet(ids::next),
                idempotencyKey, reason, evidenceReference);
    }

    private static AssetCustodian custodian(AssetTransitionRequest body) {
        if (body.custodianKind() == null || body.custodianKind().isBlank()) {
            throw new IllegalArgumentException("custodianKind is required for this transition");
        }
        AssetCustodianKind kind = AssetCustodianKind.parse(body.custodianKind());
        return kind == AssetCustodianKind.NONE
                ? AssetCustodian.none()
                : new AssetCustodian(kind, id(body.custodianId()));
    }

    private static ResponseEntity<AssetResponse> assetResponse(Asset asset, HttpStatus status) {
        return ResponseEntity.status(status).eTag(etag(asset.version())).body(AssetResponse.from(asset));
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
            throw new IllegalArgumentException("If-Match must contain a positive Asset version", failure);
        }
    }

    private static AssetType nullableType(String value) {
        return value == null || value.isBlank() ? null : AssetType.parse(value);
    }

    private static AssetLifecycleStatus nullableStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try { return AssetLifecycleStatus.valueOf(normalized); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unsupported lifecycleStatus", failure); }
    }

    private static String etag(long version) { return "\"ver-" + version + "\""; }
    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(Objects.requireNonNull(value, "identifier")); }
    private static DomainIdentifier nullableId(String value) { return value == null || value.isBlank() ? null : id(value); }
    private static String text(DomainIdentifier value) { return value == null ? null : value.toString(); }
}
