package io.infranexum.server.itam;

import io.infranexum.itam.compliance.domain.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** HTTP DTOs for PGM-07-E03 warranties, support coverage, licenses and contractual evidence. */
final class ItamComplianceApiModels {
    private ItamComplianceApiModels() {}

    record WarrantyRequest(@NotBlank String manufacturerPartnerId,@NotBlank String warrantyTypeId,
            @NotBlank @Size(min=2,max=120) String coverageLevel,@NotNull LocalDate warrantyStartDate,
            @NotNull LocalDate warrantyEndDate,@NotNull LocalDate manufacturerSupportEndDate,
            @Size(max=160) String contractOrCertificateNumber,@NotBlank @Size(min=2,max=240) String proofReference,
            @NotBlank String source,@NotBlank @Size(min=2,max=1024) String reason) {}
    record LicenseRequest(@NotBlank String publisherPartnerId,@NotBlank @Size(max=160) String contractNumber,
            @NotBlank @Size(max=120) String licenseModel,@NotBlank @Size(max=2000) String usageRights,
            @Min(1) long entitlementQuantity,@NotNull LocalDate startsOn,LocalDate endsOn,
            @NotNull LocalDate publisherSupportEndDate,@NotBlank @Size(min=2,max=240) String proofReference,
            @NotBlank String source,@NotBlank @Size(min=2,max=1024) String reason) {}
    record SupportAuthorizationRequest(@NotBlank String providerPartnerId,@NotBlank String organizationId,
            @NotEmpty Set<String> supportedManufacturerIds,@NotEmpty Set<@NotBlank String> supportedObjectTypes,
            Set<String> subdivisionScopes,@NotBlank @Size(max=240) String serviceHours,@NotBlank @Size(max=80) String timeZoneId,
            @NotEmpty Set<@NotBlank String> serviceLevels,@NotEmpty Set<@NotBlank String> escalationContactTypes,
            @NotNull LocalDate validFrom,LocalDate validUntil,@NotBlank @Size(min=2,max=1024) String reason) {}
    record SupportCoverageRequest(@NotBlank String providerPartnerId,@NotBlank String authorizationId,
            @Size(max=160) String contractReference,@NotBlank @Size(max=120) String coverageType,
            @NotBlank @Size(max=160) String serviceLevel,@NotNull LocalDate startsOn,@NotNull LocalDate endsOn,
            @NotBlank @Size(min=2,max=240) String proofReference,@NotBlank @Size(min=2,max=1024) String reason) {}
    record ReasonRequest(@NotBlank @Size(min=2,max=1024) String reason) {}
    record WarrantyTypeRequest(@NotBlank String organizationId,@Size(min=2,max=64) String code,@NotBlank @Size(min=2,max=160) String displayName,
            @NotBlank @Size(min=2,max=1024) String reason) {}

    record WarrantyResponse(String id,String assetId,String manufacturerPartnerId,String warrantyTypeId,String coverageLevel,
            LocalDate warrantyStartDate,LocalDate warrantyEndDate,LocalDate manufacturerSupportEndDate,String contractOrCertificateNumber,
            String proofReference,String source,String status,Instant verifiedAt,String verifiedBy,long version,Instant createdAt,Instant updatedAt){
        static WarrantyResponse from(Warranty w){return new WarrantyResponse(w.id().toString(),w.assetId().toString(),w.manufacturerPartnerId().toString(),w.warrantyTypeId().toString(),w.coverageLevel(),w.warrantyStartDate(),w.warrantyEndDate(),w.manufacturerSupportEndDate(),w.contractOrCertificateNumber(),w.proofReference(),w.source().wireValue(),w.status().wireValue(),w.verifiedAt(),text(w.verifiedBy()),w.version(),w.createdAt(),w.updatedAt());}}
    record LicenseResponse(String id,String assetId,String publisherPartnerId,String contractNumber,String licenseModel,String usageRights,long entitlementQuantity,
            LocalDate startsOn,LocalDate endsOn,LocalDate publisherSupportEndDate,String proofReference,String source,String status,Instant verifiedAt,String verifiedBy,long version,Instant createdAt,Instant updatedAt){
        static LicenseResponse from(SoftwareLicenseContract l){return new LicenseResponse(l.id().toString(),l.assetId().toString(),l.publisherPartnerId().toString(),l.contractNumber(),l.licenseModel(),l.usageRights(),l.entitlementQuantity(),l.startsOn(),l.endsOn(),l.publisherSupportEndDate(),l.proofReference(),l.source().wireValue(),l.status().wireValue(),l.verifiedAt(),text(l.verifiedBy()),l.version(),l.createdAt(),l.updatedAt());}}
    record SupportAuthorizationResponse(String id,String providerPartnerId,String organizationId,Set<String> supportedManufacturerIds,
            Set<String> supportedObjectTypes,Set<String> subdivisionScopes,String serviceHours,String timeZoneId,Set<String> serviceLevels,
            Set<String> escalationContactTypes,LocalDate validFrom,LocalDate validUntil,String status,long version,Instant createdAt,Instant updatedAt){
        static SupportAuthorizationResponse from(SupportProviderAuthorization a){return new SupportAuthorizationResponse(a.id().toString(),a.providerPartnerId().toString(),a.organizationId().toString(),strings(a.supportedManufacturerIds()),a.supportedObjectTypes(),strings(a.subdivisionScopes()),a.serviceHours(),a.timeZoneId(),a.serviceLevels(),a.escalationContactTypes(),a.validFrom(),a.validUntil(),a.status().wireValue(),a.version(),a.createdAt(),a.updatedAt());}}
    record SupportCoverageResponse(String id,String assetId,String providerPartnerId,String authorizationId,String contractReference,String coverageType,String serviceLevel,
            LocalDate startsOn,LocalDate endsOn,String supportedManufacturerId,String supportedObjectType,String organizationId,String subdivisionId,String proofReference,
            String status,long version,Instant createdAt,Instant updatedAt){static SupportCoverageResponse from(SupportCoverage c){return new SupportCoverageResponse(c.id().toString(),c.assetId().toString(),c.providerPartnerId().toString(),c.authorizationId().toString(),c.contractReference(),c.coverageType(),c.serviceLevel(),c.startsOn(),c.endsOn(),c.supportedManufacturerId().toString(),c.supportedObjectType(),c.organizationId().toString(),text(c.subdivisionId()),c.proofReference(),c.status().wireValue(),c.version(),c.createdAt(),c.updatedAt());}}
    record WarrantyTypeResponse(String id,String code,String displayName,boolean active,Instant createdAt,String createdBy){static WarrantyTypeResponse from(WarrantyType t){return new WarrantyTypeResponse(t.id().toString(),t.code(),t.displayName(),t.active(),t.createdAt(),t.createdBy().toString());}}
    record AlertResponse(String kind,String recordId,String assetId,LocalDate dueDate,long daysRemaining,int thresholdDays){static AlertResponse from(ComplianceAlert a){return new AlertResponse(a.kind().wireValue(),a.recordId().toString(),a.assetId().toString(),a.dueDate(),a.daysRemaining(),a.thresholdDays());}}
    record RevisionResponse(String recordType,String recordId,long version,String status,String proofReference,String reason,String snapshotJson,Instant recordedAt,String recordedBy){static RevisionResponse from(ComplianceRevision r){return new RevisionResponse(r.recordType(),r.recordId().toString(),r.version(),r.status().wireValue(),r.proofReference(),r.reason(),r.snapshotJson(),r.recordedAt(),r.recordedBy().toString());}}
    record PageResponse<T>(List<T> items,String nextCursor) {}

    private static String text(io.infranexum.core.contracts.DomainIdentifier id){return id==null?null:id.toString();}
    private static Set<String> strings(Set<io.infranexum.core.contracts.DomainIdentifier> ids){return ids.stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());}
}
