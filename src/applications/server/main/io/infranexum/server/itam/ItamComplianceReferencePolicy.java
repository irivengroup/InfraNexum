package io.infranexum.server.itam;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.SupportProviderAuthorization;
import io.infranexum.itam.compliance.ports.ComplianceReferencePolicy;
import io.infranexum.itam.compliance.ports.ComplianceRepository;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.ports.SubdivisionRepository;
import io.infranexum.rsot.application.RsotQueryService;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Cross-context weak-reference validation for PGM-07-E03 contractual compliance. */
final class ItamComplianceReferencePolicy implements ComplianceReferencePolicy {
    private static final Pattern OBJECT_TYPE=Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9_-]*)+");
    private final PartnerApplicationService partners;
    private final SubdivisionRepository subdivisions;
    private final RsotQueryService rsot;
    private final ComplianceRepository compliance;

    ItamComplianceReferencePolicy(PartnerApplicationService partners,SubdivisionRepository subdivisions,
            RsotQueryService rsot,ComplianceRepository compliance){
        this.partners=Objects.requireNonNull(partners,"partners");this.subdivisions=Objects.requireNonNull(subdivisions,"subdivisions");
        this.rsot=Objects.requireNonNull(rsot,"rsot");this.compliance=Objects.requireNonNull(compliance,"compliance");
    }

    @Override public void validateManufacturer(Asset asset,DomainIdentifier id,LocalDate on){requirePartner(id,asset.owningOrganizationId(),PartnerRole.MANUFACTURER,on);}
    @Override public void validatePublisher(Asset asset,DomainIdentifier id,LocalDate on){requirePartner(id,asset.owningOrganizationId(),PartnerRole.SOFTWARE_PUBLISHER,on);}
    @Override public void validateSupportProvider(Asset asset,DomainIdentifier id,LocalDate on,Set<String> contactTypes){Partner provider=requirePartner(id,asset.owningOrganizationId(),PartnerRole.THIRD_PARTY_SUPPORT_PROVIDER,on);requireContacts(provider,contactTypes);}
    @Override public void validateSupportAuthorizationDefinition(DomainIdentifier providerId,DomainIdentifier organizationId,
            Set<DomainIdentifier> manufacturerIds,Set<String> objectTypes,Set<DomainIdentifier> subdivisionScopes,
            Set<String> escalationContactTypes,LocalDate on){
        Partner provider=requirePartner(providerId,organizationId,PartnerRole.THIRD_PARTY_SUPPORT_PROVIDER,on);requireContacts(provider,escalationContactTypes);
        for(DomainIdentifier manufacturer:manufacturerIds)requirePartner(manufacturer,organizationId,PartnerRole.MANUFACTURER,on);
        for(String objectType:objectTypes)if(!OBJECT_TYPE.matcher(objectType).matches())throw new ComplianceConflictException("ITAM_SUPPORT_OBJECT_TYPE_INVALID","support authorization contains an invalid RSOT object type");
        for(DomainIdentifier subdivision:subdivisionScopes)if(subdivisions.findById(organizationId,subdivision).filter(s->s.state()!=SubdivisionState.DELETED).isEmpty())
            throw new ComplianceConflictException("ITAM_SUPPORT_SUBDIVISION_INVALID","support authorization subdivision is outside the provider organization");
    }
    @Override public String canonicalObjectType(Asset asset){var canonical=rsot.get(asset.rsotObjectId(),true);if(!canonical.organizationId().equals(asset.owningOrganizationId()))throw new ComplianceConflictException("ITAM_COMPLIANCE_RSOT_SCOPE_MISMATCH","RSOT object scope differs from ITAM asset owner");return canonical.objectType();}
    @Override public void validateWarrantyType(DomainIdentifier id){if(compliance.findWarrantyType(id).filter(type->type.active()).isEmpty())throw new ComplianceConflictException("ITAM_WARRANTY_TYPE_INVALID","warranty type is missing or inactive");}
    @Override public void validateSupportAuthorization(Asset asset,SupportProviderAuthorization authorization,String serviceLevel,LocalDate on){
        if(!authorization.organizationId().equals(asset.owningOrganizationId()))throw new ComplianceConflictException("ITAM_SUPPORT_AUTH_SCOPE_MISMATCH","support authorization belongs to another organization");
        validateSupportProvider(asset,authorization.providerPartnerId(),on,authorization.escalationContactTypes());
        if(asset.producerPartnerId()==null||!authorization.covers(asset.producerPartnerId(),canonicalObjectType(asset),asset.owningSubdivisionId(),serviceLevel,on))
            throw new ComplianceConflictException("ITAM_SUPPORT_AUTH_SCOPE_MISMATCH","support authorization does not cover manufacturer, product, geography, service level or period");
    }

    private Partner requirePartner(DomainIdentifier id,DomainIdentifier organizationId,PartnerRole role,LocalDate on){Partner p=partners.get(Objects.requireNonNull(id,"partnerId"));if(!p.governingOrganizationId().equals(organizationId))throw new ComplianceConflictException("ITAM_COMPLIANCE_PARTNER_SCOPE_MISMATCH","partner belongs to another organization");if(!p.roles().contains(role)||!p.selectableOn(on))throw new ComplianceConflictException("ITAM_COMPLIANCE_PARTNER_NOT_AUTHORIZED","partner does not hold the required active role on the effective date");return p;}
    private static void requireContacts(Partner partner,Set<String> types){Set<String> actual=partner.contacts().stream().map(c->c.type()).collect(java.util.stream.Collectors.toSet());if(!actual.containsAll(types))throw new ComplianceConflictException("ITAM_SUPPORT_ESCALATION_CONTACT_MISSING","support provider lacks a required escalation contact type");}
}
