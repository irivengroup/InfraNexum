package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Evidenced third-party support coverage snapshot for one ITAM asset. */
public final class SupportCoverage {
    private final DomainIdentifier id, assetId, providerPartnerId, authorizationId, supportedManufacturerId, organizationId, subdivisionId;
    private final String contractReference, coverageType, serviceLevel, supportedObjectType, proofReference;
    private final LocalDate startsOn, endsOn;
    private final ComplianceStatus status;
    private final long version;
    private final Instant createdAt, updatedAt;
    private final DomainIdentifier createdBy, updatedBy;
    private final String lastReason;

    private SupportCoverage(DomainIdentifier id,DomainIdentifier assetId,DomainIdentifier providerPartnerId,DomainIdentifier authorizationId,
            String contractReference,String coverageType,String serviceLevel,LocalDate startsOn,LocalDate endsOn,DomainIdentifier manufacturerId,
            String objectType,DomainIdentifier organizationId,DomainIdentifier subdivisionId,String proofReference,ComplianceStatus status,long version,
            Instant createdAt,Instant updatedAt,DomainIdentifier createdBy,DomainIdentifier updatedBy,String lastReason){
        this.id=Objects.requireNonNull(id,"id");this.assetId=Objects.requireNonNull(assetId,"assetId");this.providerPartnerId=Objects.requireNonNull(providerPartnerId,"providerPartnerId");
        this.authorizationId=Objects.requireNonNull(authorizationId,"authorizationId");this.contractReference=ComplianceTexts.optional(contractReference,"contractReference",160);
        this.coverageType=ComplianceTexts.text(coverageType,"coverageType",2,120);this.serviceLevel=ComplianceTexts.text(serviceLevel,"serviceLevel",1,160);
        this.startsOn=Objects.requireNonNull(startsOn,"startsOn");this.endsOn=Objects.requireNonNull(endsOn,"endsOn");if(endsOn.isBefore(startsOn))throw new IllegalArgumentException("endsOn precedes startsOn");
        this.supportedManufacturerId=Objects.requireNonNull(manufacturerId,"supportedManufacturerId");this.supportedObjectType=ComplianceTexts.text(objectType,"supportedObjectType",1,160);
        this.organizationId=Objects.requireNonNull(organizationId,"organizationId");this.subdivisionId=subdivisionId;this.proofReference=ComplianceTexts.text(proofReference,"proofReference",2,240);
        this.status=Objects.requireNonNull(status,"status");if(version<1)throw new IllegalArgumentException("version must be positive");this.version=version;
        this.createdAt=Objects.requireNonNull(createdAt,"createdAt");this.updatedAt=Objects.requireNonNull(updatedAt,"updatedAt");if(updatedAt.isBefore(createdAt))throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy=Objects.requireNonNull(createdBy,"createdBy");this.updatedBy=Objects.requireNonNull(updatedBy,"updatedBy");this.lastReason=ComplianceTexts.text(lastReason,"lastReason",2,1024);
    }
    public static SupportCoverage draft(DomainIdentifier id,DomainIdentifier assetId,DomainIdentifier providerId,DomainIdentifier authorizationId,
            String contractReference,String coverageType,String serviceLevel,LocalDate startsOn,LocalDate endsOn,DomainIdentifier manufacturerId,
            String objectType,DomainIdentifier organizationId,DomainIdentifier subdivisionId,String proofReference,DomainIdentifier actor,String reason,Instant now){
        return new SupportCoverage(id,assetId,providerId,authorizationId,contractReference,coverageType,serviceLevel,startsOn,endsOn,manufacturerId,objectType,
                organizationId,subdivisionId,proofReference,ComplianceStatus.DRAFT,1,now,now,actor,actor,reason);
    }
    public static SupportCoverage restore(DomainIdentifier id,DomainIdentifier assetId,DomainIdentifier providerId,DomainIdentifier authorizationId,
            String contractReference,String coverageType,String serviceLevel,LocalDate startsOn,LocalDate endsOn,DomainIdentifier manufacturerId,
            String objectType,DomainIdentifier organizationId,DomainIdentifier subdivisionId,String proofReference,ComplianceStatus status,long version,
            Instant createdAt,Instant updatedAt,DomainIdentifier createdBy,DomainIdentifier updatedBy,String lastReason){
        return new SupportCoverage(id,assetId,providerId,authorizationId,contractReference,coverageType,serviceLevel,startsOn,endsOn,manufacturerId,objectType,
                organizationId,subdivisionId,proofReference,status,version,createdAt,updatedAt,createdBy,updatedBy,lastReason);
    }
    public SupportCoverage revise(String contractReference,String coverageType,String serviceLevel,LocalDate startsOn,LocalDate endsOn,
            String proofReference,DomainIdentifier actor,String reason,Instant now){
        if(status==ComplianceStatus.EXPIRED||status==ComplianceStatus.CANCELLED||status==ComplianceStatus.SUPERSEDED)throw state("revise");
        return new SupportCoverage(id,assetId,providerPartnerId,authorizationId,contractReference,coverageType,serviceLevel,startsOn,endsOn,
                supportedManufacturerId,supportedObjectType,organizationId,subdivisionId,proofReference,ComplianceStatus.DRAFT,
                Math.addExact(version,1),createdAt,now,createdBy,actor,reason);
    }
    public SupportCoverage activate(DomainIdentifier actor,String reason,Instant now){if(status!=ComplianceStatus.DRAFT&&status!=ComplianceStatus.REVIEW_REQUIRED)throw state("activate");return copy(ComplianceStatus.ACTIVE,actor,reason,now);}
    public SupportCoverage requireReview(DomainIdentifier actor,String reason,Instant now){if(status!=ComplianceStatus.ACTIVE)throw state("require review");return copy(ComplianceStatus.REVIEW_REQUIRED,actor,reason,now);}
    public SupportCoverage expire(DomainIdentifier actor,String reason,Instant now,LocalDate today){if(status!=ComplianceStatus.ACTIVE&&status!=ComplianceStatus.REVIEW_REQUIRED)throw state("expire");if(!today.isAfter(endsOn))throw new ComplianceConflictException("ITAM_SUPPORT_COVERAGE_NOT_EXPIRED","coverage end date has not passed");return copy(ComplianceStatus.EXPIRED,actor,reason,now);}
    public boolean covers(LocalDate date){return status==ComplianceStatus.ACTIVE&&!date.isBefore(startsOn)&&!date.isAfter(endsOn);}
    private SupportCoverage copy(ComplianceStatus target,DomainIdentifier actor,String reason,Instant now){return new SupportCoverage(id,assetId,providerPartnerId,authorizationId,contractReference,coverageType,serviceLevel,startsOn,endsOn,supportedManufacturerId,supportedObjectType,organizationId,subdivisionId,proofReference,target,Math.addExact(version,1),createdAt,now,createdBy,actor,reason);}
    private ComplianceConflictException state(String op){return new ComplianceConflictException("ITAM_SUPPORT_COVERAGE_STATE_CONFLICT","support coverage cannot "+op+" from "+status.wireValue());}
    public DomainIdentifier id(){return id;} public DomainIdentifier assetId(){return assetId;} public DomainIdentifier providerPartnerId(){return providerPartnerId;} public DomainIdentifier authorizationId(){return authorizationId;}
    public String contractReference(){return contractReference;} public String coverageType(){return coverageType;} public String serviceLevel(){return serviceLevel;} public LocalDate startsOn(){return startsOn;} public LocalDate endsOn(){return endsOn;}
    public DomainIdentifier supportedManufacturerId(){return supportedManufacturerId;} public String supportedObjectType(){return supportedObjectType;} public DomainIdentifier organizationId(){return organizationId;} public DomainIdentifier subdivisionId(){return subdivisionId;}
    public String proofReference(){return proofReference;} public ComplianceStatus status(){return status;} public long version(){return version;} public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;}
    public DomainIdentifier createdBy(){return createdBy;} public DomainIdentifier updatedBy(){return updatedBy;} public String lastReason(){return lastReason;}
}
