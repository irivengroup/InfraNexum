package io.infranexum.server.itam;

import io.infranexum.server.http.AuthenticatedActorContext;
import static io.infranexum.server.itam.ItamComplianceApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.compliance.application.*;
import io.infranexum.itam.compliance.domain.*;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.identityaccess.ScopedAuthorizationGuard;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Organization-scoped HTTP boundary for PGM-07-E03 warranty, support and software-license governance. */
@RestController
@ConditionalOnProperty(name="infranexum.itam.compliance-api-enabled",havingValue="true")
@RequestMapping("/api/v1/itam")
public final class ItamComplianceController {
    private final ComplianceApplicationService compliance;
    private final AssetApplicationService assets;
    private final ScopedAuthorizationGuard authorization;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ItamComplianceController(ComplianceApplicationService compliance,AssetApplicationService assets,
            ScopedAuthorizationGuard authorization,@Qualifier("correlationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock){
        this.compliance=Objects.requireNonNull(compliance,"compliance");this.assets=Objects.requireNonNull(assets,"assets");
        this.authorization=Objects.requireNonNull(authorization,"authorization");this.ids=Objects.requireNonNull(ids,"ids");this.clock=Objects.requireNonNull(clock,"clock");
    }

    @GetMapping("/assets/{assetId}/warranties")
    PageResponse<WarrantyResponse> warranties(@PathVariable String assetId,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="50") int limit,HttpServletRequest request,HttpServletResponse response){
        Asset asset=requireAsset(assetId,PermissionCodes.ITAM_WARRANTY_READ,request,response);var page=compliance.warrantyPage(asset.id(),nullableId(cursor),limit);
        return new PageResponse<>(page.items().stream().map(WarrantyResponse::from).toList(),text(page.nextAfterId()));
    }

    @PostMapping("/assets/{assetId}/warranties")
    ResponseEntity<WarrantyResponse> createWarranty(@PathVariable String assetId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody WarrantyRequest body,HttpServletRequest request,HttpServletResponse response){
        Asset asset=requireAsset(assetId,PermissionCodes.ITAM_WARRANTY_MANAGE,request,response);
        Warranty warranty=compliance.createWarranty(warrantyCommand(asset.id(),body),context(request,key,body.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(warranty.version())).body(WarrantyResponse.from(warranty));
    }

    @GetMapping("/warranties/{warrantyId}")
    ResponseEntity<WarrantyResponse> getWarranty(@PathVariable String warrantyId,HttpServletRequest request,HttpServletResponse response){
        Warranty current=compliance.getWarranty(id(warrantyId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_WARRANTY_READ,request,response);return ok(current.version(),WarrantyResponse.from(current));
    }

    @PatchMapping("/warranties/{warrantyId}")
    ResponseEntity<WarrantyResponse> reviseWarranty(@PathVariable String warrantyId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody WarrantyRequest body,HttpServletRequest request,HttpServletResponse response){
        Warranty current=compliance.getWarranty(id(warrantyId));Asset asset=requireAsset(current.assetId().toString(),PermissionCodes.ITAM_WARRANTY_MANAGE,request,response);
        Warranty changed=compliance.reviseWarranty(current.id(),version(ifMatch),warrantyCommand(asset.id(),body),context(request,key,body.reason()));
        return ok(changed.version(),WarrantyResponse.from(changed));
    }

    @PostMapping("/warranties/{warrantyId}/activate")
    ResponseEntity<WarrantyResponse> activateWarranty(@PathVariable String warrantyId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){
        Warranty current=compliance.getWarranty(id(warrantyId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_WARRANTY_MANAGE,request,response);
        Warranty changed=compliance.activateWarranty(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),WarrantyResponse.from(changed));
    }

    @PostMapping("/warranties/{warrantyId}/expire")
    ResponseEntity<WarrantyResponse> expireWarranty(@PathVariable String warrantyId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){Warranty current=compliance.getWarranty(id(warrantyId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_WARRANTY_MANAGE,request,response);Warranty changed=compliance.expireWarranty(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),WarrantyResponse.from(changed));}

    @GetMapping("/assets/{assetId}/licenses")
    PageResponse<LicenseResponse> licenses(@PathVariable String assetId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit,
            HttpServletRequest request,HttpServletResponse response){Asset asset=requireAsset(assetId,PermissionCodes.ITAM_LICENSE_READ,request,response);var page=compliance.licensePage(asset.id(),nullableId(cursor),limit);return new PageResponse<>(page.items().stream().map(LicenseResponse::from).toList(),text(page.nextAfterId()));}

    @PostMapping("/assets/{assetId}/licenses")
    ResponseEntity<LicenseResponse> createLicense(@PathVariable String assetId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LicenseRequest body,HttpServletRequest request,HttpServletResponse response){Asset asset=requireAsset(assetId,PermissionCodes.ITAM_LICENSE_MANAGE,request,response);SoftwareLicenseContract license=compliance.createLicense(licenseCommand(asset.id(),body),context(request,key,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(license.version())).body(LicenseResponse.from(license));}

    @GetMapping("/licenses/{licenseId}")
    ResponseEntity<LicenseResponse> getLicense(@PathVariable String licenseId,HttpServletRequest request,HttpServletResponse response){
        SoftwareLicenseContract current=compliance.getLicense(id(licenseId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_LICENSE_READ,request,response);return ok(current.version(),LicenseResponse.from(current));
    }

    @PatchMapping("/licenses/{licenseId}")
    ResponseEntity<LicenseResponse> reviseLicense(@PathVariable String licenseId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody LicenseRequest body,HttpServletRequest request,HttpServletResponse response){SoftwareLicenseContract current=compliance.getLicense(id(licenseId));Asset asset=requireAsset(current.assetId().toString(),PermissionCodes.ITAM_LICENSE_MANAGE,request,response);SoftwareLicenseContract changed=compliance.reviseLicense(current.id(),version(ifMatch),licenseCommand(asset.id(),body),context(request,key,body.reason()));return ok(changed.version(),LicenseResponse.from(changed));}

    @PostMapping("/licenses/{licenseId}/activate")
    ResponseEntity<LicenseResponse> activateLicense(@PathVariable String licenseId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SoftwareLicenseContract current=compliance.getLicense(id(licenseId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_LICENSE_MANAGE,request,response);SoftwareLicenseContract changed=compliance.activateLicense(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),LicenseResponse.from(changed));}

    @PostMapping("/licenses/{licenseId}/expire")
    ResponseEntity<LicenseResponse> expireLicense(@PathVariable String licenseId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SoftwareLicenseContract current=compliance.getLicense(id(licenseId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_LICENSE_MANAGE,request,response);SoftwareLicenseContract changed=compliance.expireLicense(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),LicenseResponse.from(changed));}

    @GetMapping("/assets/{assetId}/support-coverages")
    PageResponse<SupportCoverageResponse> supportCoverages(@PathVariable String assetId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit,
            HttpServletRequest request,HttpServletResponse response){Asset asset=requireAsset(assetId,PermissionCodes.ITAM_SUPPORT_COVERAGE_READ,request,response);var page=compliance.supportCoveragePage(asset.id(),nullableId(cursor),limit);return new PageResponse<>(page.items().stream().map(SupportCoverageResponse::from).toList(),text(page.nextAfterId()));}

    @PostMapping("/assets/{assetId}/support-coverages")
    ResponseEntity<SupportCoverageResponse> createSupportCoverage(@PathVariable String assetId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SupportCoverageRequest body,HttpServletRequest request,HttpServletResponse response){Asset asset=requireAsset(assetId,PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE,request,response);SupportCoverage coverage=compliance.createSupportCoverage(coverageCommand(asset.id(),body),context(request,key,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(coverage.version())).body(SupportCoverageResponse.from(coverage));}

    @GetMapping("/support-coverages/{coverageId}")
    ResponseEntity<SupportCoverageResponse> getSupportCoverage(@PathVariable String coverageId,HttpServletRequest request,HttpServletResponse response){
        SupportCoverage current=compliance.getSupportCoverage(id(coverageId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_SUPPORT_COVERAGE_READ,request,response);return ok(current.version(),SupportCoverageResponse.from(current));
    }

    @PatchMapping("/support-coverages/{coverageId}")
    ResponseEntity<SupportCoverageResponse> reviseSupportCoverage(@PathVariable String coverageId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SupportCoverageRequest body,HttpServletRequest request,HttpServletResponse response){SupportCoverage current=compliance.getSupportCoverage(id(coverageId));Asset asset=requireAsset(current.assetId().toString(),PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE,request,response);SupportCoverage changed=compliance.reviseSupportCoverage(current.id(),version(ifMatch),coverageCommand(asset.id(),body),context(request,key,body.reason()));return ok(changed.version(),SupportCoverageResponse.from(changed));}

    @PostMapping("/support-coverages/{coverageId}/activate")
    ResponseEntity<SupportCoverageResponse> activateSupportCoverage(@PathVariable String coverageId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SupportCoverage current=compliance.getSupportCoverage(id(coverageId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE,request,response);SupportCoverage changed=compliance.activateSupportCoverage(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),SupportCoverageResponse.from(changed));}

    @PostMapping("/support-coverages/{coverageId}/expire")
    ResponseEntity<SupportCoverageResponse> expireSupportCoverage(@PathVariable String coverageId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SupportCoverage current=compliance.getSupportCoverage(id(coverageId));requireAsset(current.assetId().toString(),PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE,request,response);SupportCoverage changed=compliance.expireSupportCoverage(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),SupportCoverageResponse.from(changed));}

    @GetMapping("/support-authorizations")
    ResponseEntity<List<SupportAuthorizationResponse>> supportAuthorizations(@RequestParam(name="organization_id") String organizationId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,HttpServletRequest request,HttpServletResponse response){DomainIdentifier org=id(organizationId);authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(org),"itam-support-authorization","collection");var page=compliance.supportAuthorizationPage(org,offset,limit);return ApiPagination.offset(page.items().stream().map(SupportAuthorizationResponse::from).toList(),page.nextOffset(),limit);}

    @PostMapping("/support-authorizations")
    ResponseEntity<SupportAuthorizationResponse> createSupportAuthorization(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SupportAuthorizationRequest body,HttpServletRequest request,HttpServletResponse response){DomainIdentifier org=id(body.organizationId());authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(org),"itam-support-authorization","collection");SupportProviderAuthorization a=compliance.createSupportAuthorization(authCommand(body),context(request,key,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(a.version())).body(SupportAuthorizationResponse.from(a));}

    @GetMapping("/support-authorizations/{authorizationId}")
    ResponseEntity<SupportAuthorizationResponse> getSupportAuthorization(@PathVariable String authorizationId,HttpServletRequest request,HttpServletResponse response){SupportProviderAuthorization a=compliance.getSupportAuthorization(id(authorizationId));authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(a.organizationId()),"itam-support-authorization",a.id().toString());return ok(a.version(),SupportAuthorizationResponse.from(a));}

    @PostMapping("/support-authorizations/{authorizationId}/activate")
    ResponseEntity<SupportAuthorizationResponse> activateSupportAuthorization(@PathVariable String authorizationId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SupportProviderAuthorization current=compliance.getSupportAuthorization(id(authorizationId));authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(current.organizationId()),"itam-support-authorization",current.id().toString());SupportProviderAuthorization changed=compliance.activateSupportAuthorization(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),SupportAuthorizationResponse.from(changed));}

    @PostMapping("/support-authorizations/{authorizationId}/suspend")
    ResponseEntity<SupportAuthorizationResponse> suspendSupportAuthorization(@PathVariable String authorizationId,@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReasonRequest body,HttpServletRequest request,HttpServletResponse response){SupportProviderAuthorization current=compliance.getSupportAuthorization(id(authorizationId));authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(current.organizationId()),"itam-support-authorization",current.id().toString());SupportProviderAuthorization changed=compliance.suspendSupportAuthorization(current.id(),version(ifMatch),context(request,key,body.reason()));return ok(changed.version(),SupportAuthorizationResponse.from(changed));}

    @GetMapping("/warranty-types")
    ResponseEntity<List<WarrantyTypeResponse>> warrantyTypes(@RequestParam(name="organization_id") String organizationId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,HttpServletRequest request,HttpServletResponse response){DomainIdentifier org=id(organizationId);authorization.require(request,response,PermissionCodes.ITAM_WARRANTY_READ,AuthorizationScope.organization(org),"itam-warranty-type","collection");var page=compliance.warrantyTypePage(offset,limit);return ApiPagination.offset(page.items().stream().map(WarrantyTypeResponse::from).toList(),page.nextOffset(),limit);}

    @PostMapping("/warranty-types")
    ResponseEntity<WarrantyTypeResponse> createWarrantyType(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody WarrantyTypeRequest body,HttpServletRequest request,HttpServletResponse response){DomainIdentifier org=id(body.organizationId());authorization.require(request,response,PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE,AuthorizationScope.organization(org),"itam-warranty-type","collection");WarrantyType type=compliance.createWarrantyType(body.code(),body.displayName(),context(request,key,body.reason()));return ResponseEntity.status(HttpStatus.CREATED).body(WarrantyTypeResponse.from(type));}

    @GetMapping("/assets/{assetId}/compliance-alerts")
    ResponseEntity<List<AlertResponse>> alerts(@PathVariable String assetId,@RequestParam(name="as_of",required=false) LocalDate asOf,@RequestParam(name="horizon_days",defaultValue="180") int horizon,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,HttpServletRequest request,HttpServletResponse response){Asset asset=requireAsset(assetId,PermissionCodes.ITAM_AUDIT_READ,request,response);var page=compliance.upcomingAlertPage(asset.id(),asOf==null?LocalDate.now(clock):asOf,horizon,offset,limit);return ApiPagination.offset(page.items().stream().map(AlertResponse::from).toList(),page.nextOffset(),limit);}

    @GetMapping("/{recordType:warranties|licenses|support-coverages}/{recordId}/history")
    List<RevisionResponse> history(@PathVariable String recordType,@PathVariable String recordId,@RequestParam(name="after_version",defaultValue="0") long afterVersion,@RequestParam(defaultValue="100") int limit,HttpServletRequest request,HttpServletResponse response){String internal=switch(recordType){case "warranties"->"warranty";case "licenses"->"license";case "support-coverages"->"support_coverage";default->throw new IllegalArgumentException("unsupported record type");};DomainIdentifier assetId=switch(internal){case "warranty"->compliance.getWarranty(id(recordId)).assetId();case "license"->compliance.getLicense(id(recordId)).assetId();case "support_coverage"->compliance.getSupportCoverage(id(recordId)).assetId();default->throw new IllegalStateException();};requireAsset(assetId.toString(),PermissionCodes.ITAM_AUDIT_READ,request,response);return compliance.history(internal,id(recordId),afterVersion,limit).stream().map(RevisionResponse::from).toList();}

    private Asset requireAsset(String assetId,String permission,HttpServletRequest request,HttpServletResponse response){Asset asset=assets.get(id(assetId));authorization.require(request,response,permission,AuthorizationScope.organization(asset.owningOrganizationId()),"itam-asset",asset.id().toString());return asset;}
    private ComplianceCommandContext context(HttpServletRequest request,String key,String reason){Object actor=request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);if(!(actor instanceof DomainIdentifier actorId))throw new IllegalStateException("authenticated actor missing after RBAC boundary");return new ComplianceCommandContext(actorId,CorrelationContext.identifier(request).orElseGet(ids::next),key,reason);}
    private static CreateWarrantyCommand warrantyCommand(DomainIdentifier assetId,WarrantyRequest b){return new CreateWarrantyCommand(assetId,id(b.manufacturerPartnerId()),id(b.warrantyTypeId()),b.coverageLevel(),b.warrantyStartDate(),b.warrantyEndDate(),b.manufacturerSupportEndDate(),b.contractOrCertificateNumber(),b.proofReference(),b.source());}
    private static CreateLicenseCommand licenseCommand(DomainIdentifier assetId,LicenseRequest b){return new CreateLicenseCommand(assetId,id(b.publisherPartnerId()),b.contractNumber(),b.licenseModel(),b.usageRights(),b.entitlementQuantity(),b.startsOn(),b.endsOn(),b.publisherSupportEndDate(),b.proofReference(),b.source());}
    private static CreateSupportCoverageCommand coverageCommand(DomainIdentifier assetId,SupportCoverageRequest b){return new CreateSupportCoverageCommand(assetId,id(b.providerPartnerId()),id(b.authorizationId()),b.contractReference(),b.coverageType(),b.serviceLevel(),b.startsOn(),b.endsOn(),b.proofReference());}
    private static CreateSupportAuthorizationCommand authCommand(SupportAuthorizationRequest b){return new CreateSupportAuthorizationCommand(id(b.providerPartnerId()),id(b.organizationId()),ids(b.supportedManufacturerIds()),b.supportedObjectTypes(),ids(b.subdivisionScopes()),b.serviceHours(),b.timeZoneId(),b.serviceLevels(),b.escalationContactTypes(),b.validFrom(),b.validUntil());}
    private static <T>ResponseEntity<T> ok(long version,T body){return ResponseEntity.ok().eTag(etag(version)).body(body);}
    private static long version(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("If-Match is required");String v=value.strip();if(v.startsWith("W/"))v=v.substring(2).strip();if(v.startsWith("\"")&&v.endsWith("\"")&&v.length()>1)v=v.substring(1,v.length()-1);if(v.toLowerCase(Locale.ROOT).startsWith("ver-"))v=v.substring(4);try{long parsed=Long.parseLong(v);if(parsed<1)throw new NumberFormatException();return parsed;}catch(NumberFormatException e){throw new IllegalArgumentException("If-Match must contain a positive compliance version",e);}}
    private static String etag(long version){return "\"ver-"+version+"\"";}
    private static DomainIdentifier id(String value){return DomainIdentifier.parse(Objects.requireNonNull(value,"identifier"));}
    private static DomainIdentifier nullableId(String value){return value==null||value.isBlank()?null:id(value);}
    private static Set<DomainIdentifier> ids(Set<String> values){if(values==null||values.isEmpty())return Set.of();return values.stream().map(ItamComplianceController::id).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private static String text(DomainIdentifier value){return value==null?null:value.toString();}
}
