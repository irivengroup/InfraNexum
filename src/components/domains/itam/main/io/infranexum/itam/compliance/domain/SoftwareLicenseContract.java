package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Software-license contract evidence; secret license keys are intentionally outside this aggregate. */
public final class SoftwareLicenseContract {
    private final DomainIdentifier id, assetId, publisherPartnerId;
    private final String contractNumber, licenseModel, usageRights, proofReference;
    private final long entitlementQuantity;
    private final LocalDate startsOn, endsOn, publisherSupportEndDate;
    private final EvidenceSource source;
    private final ComplianceStatus status;
    private final Instant verifiedAt;
    private final DomainIdentifier verifiedBy;
    private final long version;
    private final Instant createdAt, updatedAt;
    private final DomainIdentifier createdBy, updatedBy;
    private final String lastReason;

    private SoftwareLicenseContract(
            DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier publisherPartnerId, String contractNumber,
            String licenseModel, String usageRights, long entitlementQuantity, LocalDate startsOn, LocalDate endsOn,
            LocalDate publisherSupportEndDate, String proofReference, EvidenceSource source, ComplianceStatus status,
            Instant verifiedAt, DomainIdentifier verifiedBy, long version, Instant createdAt, Instant updatedAt,
            DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        this.id=Objects.requireNonNull(id,"id"); this.assetId=Objects.requireNonNull(assetId,"assetId");
        this.publisherPartnerId=Objects.requireNonNull(publisherPartnerId,"publisherPartnerId");
        this.contractNumber=ComplianceTexts.text(contractNumber,"contractNumber",2,160);
        this.licenseModel=ComplianceTexts.text(licenseModel,"licenseModel",2,120);
        this.usageRights=ComplianceTexts.text(usageRights,"usageRights",2,2048);
        if(entitlementQuantity<1) throw new IllegalArgumentException("entitlementQuantity must be positive"); this.entitlementQuantity=entitlementQuantity;
        this.startsOn=Objects.requireNonNull(startsOn,"startsOn"); this.endsOn=endsOn;
        if(endsOn!=null && endsOn.isBefore(startsOn)) throw new IllegalArgumentException("endsOn precedes startsOn");
        this.publisherSupportEndDate=Objects.requireNonNull(publisherSupportEndDate,"publisherSupportEndDate");
        if(publisherSupportEndDate.isBefore(startsOn)) throw new IllegalArgumentException("publisherSupportEndDate precedes startsOn");
        this.proofReference=ComplianceTexts.text(proofReference,"proofReference",2,240); this.source=Objects.requireNonNull(source,"source");
        this.status=Objects.requireNonNull(status,"status");
        if(status.verifiedState() && (verifiedAt==null || verifiedBy==null)) throw new IllegalArgumentException("verified license state requires verifier and timestamp");
        this.verifiedAt=verifiedAt; this.verifiedBy=verifiedBy;
        if(version<1) throw new IllegalArgumentException("version must be positive"); this.version=version;
        this.createdAt=Objects.requireNonNull(createdAt,"createdAt"); this.updatedAt=Objects.requireNonNull(updatedAt,"updatedAt");
        if(updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy=Objects.requireNonNull(createdBy,"createdBy"); this.updatedBy=Objects.requireNonNull(updatedBy,"updatedBy");
        this.lastReason=ComplianceTexts.text(lastReason,"lastReason",2,1024);
    }

    public static SoftwareLicenseContract draft(DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier publisherPartnerId,
            String contractNumber, String licenseModel, String usageRights, long entitlementQuantity, LocalDate startsOn,
            LocalDate endsOn, LocalDate supportEndDate, String proofReference, EvidenceSource source,
            DomainIdentifier actorId, String reason, Instant now) {
        return new SoftwareLicenseContract(id,assetId,publisherPartnerId,contractNumber,licenseModel,usageRights,entitlementQuantity,
                startsOn,endsOn,supportEndDate,proofReference,source,ComplianceStatus.DRAFT,null,null,1,now,now,actorId,actorId,reason);
    }
    public static SoftwareLicenseContract restore(DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier publisherPartnerId,
            String contractNumber, String licenseModel, String usageRights, long entitlementQuantity, LocalDate startsOn,
            LocalDate endsOn, LocalDate supportEndDate, String proofReference, EvidenceSource source, ComplianceStatus status,
            Instant verifiedAt, DomainIdentifier verifiedBy, long version, Instant createdAt, Instant updatedAt,
            DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        return new SoftwareLicenseContract(id,assetId,publisherPartnerId,contractNumber,licenseModel,usageRights,entitlementQuantity,
                startsOn,endsOn,supportEndDate,proofReference,source,status,verifiedAt,verifiedBy,version,createdAt,updatedAt,createdBy,updatedBy,lastReason);
    }
    public SoftwareLicenseContract revise(DomainIdentifier publisherPartnerId,String contractNumber,String licenseModel,String usageRights,
            long entitlementQuantity,LocalDate startsOn,LocalDate endsOn,LocalDate supportEndDate,String proofReference,EvidenceSource source,
            DomainIdentifier actorId,String reason,Instant now){
        if(status!=ComplianceStatus.DRAFT && status!=ComplianceStatus.ACTIVE) throw state("revise");
        return new SoftwareLicenseContract(id,assetId,publisherPartnerId,contractNumber,licenseModel,usageRights,entitlementQuantity,startsOn,
                endsOn,supportEndDate,proofReference,source,ComplianceStatus.DRAFT,null,null,Math.addExact(version,1),createdAt,now,createdBy,actorId,reason);
    }
    public SoftwareLicenseContract activate(DomainIdentifier verifierId,String reason,Instant now){
        if(status!=ComplianceStatus.DRAFT) throw state("activate");
        return copy(ComplianceStatus.ACTIVE,now,verifierId,Math.addExact(version,1),verifierId,reason,now);
    }
    public SoftwareLicenseContract expire(DomainIdentifier actorId,String reason,Instant now,LocalDate today){
        if(status!=ComplianceStatus.ACTIVE) throw state("expire");
        if(endsOn==null || !today.isAfter(endsOn)) throw new ComplianceConflictException("ITAM_LICENSE_NOT_EXPIRED","license contract has not expired");
        return copy(ComplianceStatus.EXPIRED,verifiedAt,verifiedBy,Math.addExact(version,1),actorId,reason,now);
    }
    public SoftwareLicenseContract cancel(DomainIdentifier actorId,String reason,Instant now){
        if(status==ComplianceStatus.CANCELLED || status==ComplianceStatus.SUPERSEDED) throw state("cancel");
        return copy(ComplianceStatus.CANCELLED,verifiedAt,verifiedBy,Math.addExact(version,1),actorId,reason,now);
    }
    public boolean covers(LocalDate date){
        // ACTIVE is constructible only with verifier evidence; repeating that invariant here creates unreachable branches.
        return status==ComplianceStatus.ACTIVE && !date.isBefore(startsOn) && (endsOn==null || !date.isAfter(endsOn));
    }
    private SoftwareLicenseContract copy(ComplianceStatus target,Instant verified,DomainIdentifier verifier,long nextVersion,
            DomainIdentifier actorId,String reason,Instant now){
        return new SoftwareLicenseContract(id,assetId,publisherPartnerId,contractNumber,licenseModel,usageRights,entitlementQuantity,startsOn,
                endsOn,publisherSupportEndDate,proofReference,source,target,verified,verifier,nextVersion,createdAt,now,createdBy,actorId,reason);
    }
    private ComplianceConflictException state(String op){return new ComplianceConflictException("ITAM_LICENSE_STATE_CONFLICT","license contract cannot "+op+" from "+status.wireValue());}
    public DomainIdentifier id(){return id;} public DomainIdentifier assetId(){return assetId;} public DomainIdentifier publisherPartnerId(){return publisherPartnerId;}
    public String contractNumber(){return contractNumber;} public String licenseModel(){return licenseModel;} public String usageRights(){return usageRights;}
    public long entitlementQuantity(){return entitlementQuantity;} public LocalDate startsOn(){return startsOn;} public LocalDate endsOn(){return endsOn;}
    public LocalDate publisherSupportEndDate(){return publisherSupportEndDate;} public String proofReference(){return proofReference;}
    public EvidenceSource source(){return source;} public ComplianceStatus status(){return status;} public Instant verifiedAt(){return verifiedAt;}
    public DomainIdentifier verifiedBy(){return verifiedBy;} public long version(){return version;} public Instant createdAt(){return createdAt;}
    public Instant updatedAt(){return updatedAt;} public DomainIdentifier createdBy(){return createdBy;} public DomainIdentifier updatedBy(){return updatedBy;}
    public String lastReason(){return lastReason;}
}
