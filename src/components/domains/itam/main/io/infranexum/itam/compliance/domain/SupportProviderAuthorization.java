package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Governed third-party support scope tied to one authorized Partner. */
public final class SupportProviderAuthorization {
    private final DomainIdentifier id, providerPartnerId, organizationId;
    private final Set<DomainIdentifier> supportedManufacturerIds, subdivisionScopes;
    private final Set<String> supportedObjectTypes, serviceLevels, escalationContactTypes;
    private final String serviceHours, timeZoneId;
    private final LocalDate validFrom, validUntil;
    private final ComplianceStatus status;
    private final long version;
    private final Instant createdAt, updatedAt;
    private final DomainIdentifier createdBy, updatedBy;
    private final String lastReason;

    private SupportProviderAuthorization(DomainIdentifier id,DomainIdentifier providerPartnerId,DomainIdentifier organizationId,
            Set<DomainIdentifier> supportedManufacturerIds,Set<String> supportedObjectTypes,Set<DomainIdentifier> subdivisionScopes,
            String serviceHours,String timeZoneId,Set<String> serviceLevels,Set<String> escalationContactTypes,
            LocalDate validFrom,LocalDate validUntil,ComplianceStatus status,long version,Instant createdAt,Instant updatedAt,
            DomainIdentifier createdBy,DomainIdentifier updatedBy,String lastReason){
        this.id=Objects.requireNonNull(id,"id");this.providerPartnerId=Objects.requireNonNull(providerPartnerId,"providerPartnerId");
        this.organizationId=Objects.requireNonNull(organizationId,"organizationId");
        this.supportedManufacturerIds=nonEmptyIds(supportedManufacturerIds,"supportedManufacturerIds");
        this.supportedObjectTypes=nonEmptyTokens(supportedObjectTypes,"supportedObjectTypes");
        this.subdivisionScopes=Set.copyOf(Objects.requireNonNull(subdivisionScopes,"subdivisionScopes"));
        this.serviceHours=ComplianceTexts.text(serviceHours,"serviceHours",2,512);
        this.timeZoneId=ZoneId.of(ComplianceTexts.text(timeZoneId,"timeZoneId",1,80)).getId();
        this.serviceLevels=nonEmptyTokens(serviceLevels,"serviceLevels");
        this.escalationContactTypes=nonEmptyTokens(escalationContactTypes,"escalationContactTypes");
        this.validFrom=Objects.requireNonNull(validFrom,"validFrom");this.validUntil=validUntil;
        if(validUntil!=null&&validUntil.isBefore(validFrom))throw new IllegalArgumentException("validUntil precedes validFrom");
        this.status=Objects.requireNonNull(status,"status");if(version<1)throw new IllegalArgumentException("version must be positive");this.version=version;
        this.createdAt=Objects.requireNonNull(createdAt,"createdAt");this.updatedAt=Objects.requireNonNull(updatedAt,"updatedAt");
        if(updatedAt.isBefore(createdAt))throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy=Objects.requireNonNull(createdBy,"createdBy");this.updatedBy=Objects.requireNonNull(updatedBy,"updatedBy");
        this.lastReason=ComplianceTexts.text(lastReason,"lastReason",2,1024);
    }
    public static SupportProviderAuthorization draft(DomainIdentifier id,DomainIdentifier provider,DomainIdentifier organization,
            Set<DomainIdentifier> manufacturers,Set<String> objectTypes,Set<DomainIdentifier> subdivisions,String serviceHours,String timeZoneId,
            Set<String> serviceLevels,Set<String> escalationContactTypes,LocalDate validFrom,LocalDate validUntil,
            DomainIdentifier actor,String reason,Instant now){
        return new SupportProviderAuthorization(id,provider,organization,manufacturers,objectTypes,subdivisions,serviceHours,timeZoneId,
                serviceLevels,escalationContactTypes,validFrom,validUntil,ComplianceStatus.DRAFT,1,now,now,actor,actor,reason);
    }
    public static SupportProviderAuthorization restore(DomainIdentifier id,DomainIdentifier provider,DomainIdentifier organization,
            Set<DomainIdentifier> manufacturers,Set<String> objectTypes,Set<DomainIdentifier> subdivisions,String serviceHours,String timeZoneId,
            Set<String> serviceLevels,Set<String> escalationContactTypes,LocalDate validFrom,LocalDate validUntil,ComplianceStatus status,
            long version,Instant createdAt,Instant updatedAt,DomainIdentifier createdBy,DomainIdentifier updatedBy,String lastReason){
        return new SupportProviderAuthorization(id,provider,organization,manufacturers,objectTypes,subdivisions,serviceHours,timeZoneId,
                serviceLevels,escalationContactTypes,validFrom,validUntil,status,version,createdAt,updatedAt,createdBy,updatedBy,lastReason);
    }
    public SupportProviderAuthorization activate(DomainIdentifier actor,String reason,Instant now,LocalDate today){
        if(status!=ComplianceStatus.DRAFT && status!=ComplianceStatus.REVIEW_REQUIRED)throw state("activate");
        if(today.isBefore(validFrom)||(validUntil!=null&&today.isAfter(validUntil)))throw new ComplianceConflictException("ITAM_SUPPORT_AUTH_PERIOD_INVALID","support authorization period excludes activation date");
        return copy(ComplianceStatus.ACTIVE,actor,reason,now);
    }
    public SupportProviderAuthorization suspend(DomainIdentifier actor,String reason,Instant now){
        if(status!=ComplianceStatus.ACTIVE)throw state("suspend"); return copy(ComplianceStatus.REVIEW_REQUIRED,actor,reason,now);
    }
    public boolean selectableOn(LocalDate date){return status==ComplianceStatus.ACTIVE&&!date.isBefore(validFrom)&&(validUntil==null||!date.isAfter(validUntil));}
    public boolean covers(DomainIdentifier manufacturer,String objectType,DomainIdentifier subdivision,String serviceLevel,LocalDate date){
        if(!selectableOn(date)||!supportedManufacturerIds.contains(manufacturer)||!supportedObjectTypes.contains(objectType)||!serviceLevels.contains(serviceLevel))return false;
        return subdivisionScopes.isEmpty() || (subdivision!=null && subdivisionScopes.contains(subdivision));
    }
    private SupportProviderAuthorization copy(ComplianceStatus target,DomainIdentifier actor,String reason,Instant now){
        return new SupportProviderAuthorization(id,providerPartnerId,organizationId,supportedManufacturerIds,supportedObjectTypes,subdivisionScopes,
                serviceHours,timeZoneId,serviceLevels,escalationContactTypes,validFrom,validUntil,target,Math.addExact(version,1),createdAt,now,createdBy,actor,reason);
    }
    private ComplianceConflictException state(String op){return new ComplianceConflictException("ITAM_SUPPORT_AUTH_STATE_CONFLICT","support authorization cannot "+op+" from "+status.wireValue());}
    private static Set<DomainIdentifier> nonEmptyIds(Set<DomainIdentifier> values,String field){Objects.requireNonNull(values,field);if(values.isEmpty())throw new IllegalArgumentException(field+" must not be empty");return Set.copyOf(values);}
    private static Set<String> nonEmptyTokens(Set<String> values,String field){Objects.requireNonNull(values,field);if(values.isEmpty())throw new IllegalArgumentException(field+" must not be empty");return values.stream().map(v->ComplianceTexts.text(v,field,1,160)).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    public DomainIdentifier id(){return id;} public DomainIdentifier providerPartnerId(){return providerPartnerId;} public DomainIdentifier organizationId(){return organizationId;}
    public Set<DomainIdentifier> supportedManufacturerIds(){return supportedManufacturerIds;} public Set<String> supportedObjectTypes(){return supportedObjectTypes;}
    public Set<DomainIdentifier> subdivisionScopes(){return subdivisionScopes;} public String serviceHours(){return serviceHours;} public String timeZoneId(){return timeZoneId;}
    public Set<String> serviceLevels(){return serviceLevels;} public Set<String> escalationContactTypes(){return escalationContactTypes;}
    public LocalDate validFrom(){return validFrom;} public LocalDate validUntil(){return validUntil;} public ComplianceStatus status(){return status;} public long version(){return version;}
    public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;} public DomainIdentifier createdBy(){return createdBy;} public DomainIdentifier updatedBy(){return updatedBy;}
    public String lastReason(){return lastReason;}
}
