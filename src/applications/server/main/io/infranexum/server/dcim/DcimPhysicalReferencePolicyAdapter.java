package io.infranexum.server.dcim;

import io.infranexum.adapters.persistence.jdbc.JdbcAssetRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcFacilityRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcPartnerRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcRsotRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import io.infranexum.dcim.physical.domain.DcimPhysicalConflictException;
import io.infranexum.dcim.physical.ports.DcimPhysicalReferencePolicy;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.SubdivisionState;
import java.util.Objects;

/** Validates every PGM-07-E05 weak reference against its authoritative bounded context. */
public final class DcimPhysicalReferencePolicyAdapter implements DcimPhysicalReferencePolicy {
    private final JdbcOrganizationRepository organizations; private final JdbcSubdivisionRepository subdivisions;
    private final JdbcFacilityRepository facilities; private final JdbcPartnerRepository partners; private final JdbcRsotRepository rsot; private final JdbcAssetRepository assets;
    public DcimPhysicalReferencePolicyAdapter(JdbcOrganizationRepository organizations,JdbcSubdivisionRepository subdivisions,JdbcFacilityRepository facilities,JdbcPartnerRepository partners,JdbcRsotRepository rsot,JdbcAssetRepository assets){this.organizations=Objects.requireNonNull(organizations,"organizations");this.subdivisions=Objects.requireNonNull(subdivisions,"subdivisions");this.facilities=Objects.requireNonNull(facilities,"facilities");this.partners=Objects.requireNonNull(partners,"partners");this.rsot=Objects.requireNonNull(rsot,"rsot");this.assets=Objects.requireNonNull(assets,"assets");}
    @Override public void requireScope(DomainIdentifier organizationId,DomainIdentifier subdivisionId){var org=organizations.findById(organizationId).orElseThrow(()->conflict("DCIM_ORGANIZATION_INVALID","organization does not exist"));if(org.state()!=OrganizationState.ACTIVE)throw conflict("DCIM_ORGANIZATION_INACTIVE","organization must be active");var sub=subdivisions.findById(organizationId,subdivisionId).orElseThrow(()->conflict("DCIM_SUBDIVISION_INVALID","subdivision does not exist in organization"));if(sub.state()!=SubdivisionState.ACTIVE)throw conflict("DCIM_SUBDIVISION_INACTIVE","subdivision must be active");}
    @Override public void requireActiveRoom(DomainIdentifier roomId,DomainIdentifier organizationId,DomainIdentifier subdivisionId){var room=facilities.findById(roomId).orElseThrow(()->conflict("DCIM_ROOM_INVALID","room does not exist"));if(room.kind()!=FacilityKind.ROOM||room.status()!=FacilityStatus.ACTIVE)throw conflict("DCIM_ROOM_INACTIVE","rack room must be an active room");if(!room.organizationId().equals(organizationId)||!room.subdivisionId().equals(subdivisionId))throw conflict("DCIM_SCOPE_MISMATCH","room belongs to another governance scope");}
    @Override public void requireManufacturer(DomainIdentifier partnerId,DomainIdentifier organizationId){var partner=partners.findById(partnerId).orElseThrow(()->conflict("DCIM_MANUFACTURER_INVALID","manufacturer partner does not exist"));if(!partner.governingOrganizationId().equals(organizationId)||partner.authorizationStatus()!=PartnerAuthorizationStatus.ACTIVE||!partner.roles().contains(PartnerRole.MANUFACTURER))throw conflict("DCIM_MANUFACTURER_INVALID","equipment model requires an active manufacturer in the same organization");}
    @Override public void requireRsotObject(DomainIdentifier rsotObjectId,DomainIdentifier organizationId){var object=rsot.findCanonicalObject(rsotObjectId).orElseThrow(()->conflict("DCIM_RSOT_INVALID","RSOT object does not exist"));if(!object.organizationId().equals(organizationId))throw conflict("DCIM_SCOPE_MISMATCH","RSOT object belongs to another organization");}
    @Override public void requireItamAssetIfPresent(DomainIdentifier assetId,DomainIdentifier organizationId){if(assetId==null)return;var asset=assets.findById(assetId).orElseThrow(()->conflict("DCIM_ITAM_ASSET_INVALID","ITAM asset does not exist"));if(!asset.owningOrganizationId().equals(organizationId))throw conflict("DCIM_SCOPE_MISMATCH","ITAM asset belongs to another organization");}
    private static DcimPhysicalConflictException conflict(String code,String message){return new DcimPhysicalConflictException(code,message);}
}
