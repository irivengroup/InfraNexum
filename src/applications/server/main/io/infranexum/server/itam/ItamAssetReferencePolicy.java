package io.infranexum.server.itam;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.ports.AssetReferencePolicy;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerRole;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import io.infranexum.rsot.application.RsotQueryService;
import java.time.LocalDate;
import java.util.Objects;

/** Cross-context weak-reference validator for the ITAM asset lifecycle. */
final class ItamAssetReferencePolicy implements AssetReferencePolicy {
    private final RsotQueryService rsot;
    private final OrganizationRepository organizations;
    private final SubdivisionRepository subdivisions;
    private final PartnerApplicationService partners;

    ItamAssetReferencePolicy(
            RsotQueryService rsot,
            OrganizationRepository organizations,
            SubdivisionRepository subdivisions,
            PartnerApplicationService partners) {
        this.rsot = Objects.requireNonNull(rsot, "rsot");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.subdivisions = Objects.requireNonNull(subdivisions, "subdivisions");
        this.partners = Objects.requireNonNull(partners, "partners");
    }

    @Override
    public void validateCanonicalObject(DomainIdentifier rsotObjectId, DomainIdentifier organizationId) {
        Objects.requireNonNull(rsotObjectId, "rsotObjectId");
        Objects.requireNonNull(organizationId, "organizationId");
        requireOrganization(organizationId);
        var canonical = rsot.get(rsotObjectId, true);
        if (!canonical.organizationId().equals(organizationId)) {
            throw new AssetConflictException(
                    "ITAM_ASSET_RSOT_SCOPE_MISMATCH", "RSOT canonical object belongs to another organization");
        }
    }

    @Override
    public void validateSubdivision(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subdivisionId, "subdivisionId");
        if (subdivisions.findById(organizationId, subdivisionId)
                .filter(value -> value.state() != SubdivisionState.DELETED).isEmpty()) {
            throw new AssetConflictException(
                    "ITAM_ASSET_SUBDIVISION_INVALID", "subdivision does not belong to the owning organization");
        }
    }

    @Override
    public void validateAcquisitionPartner(
            DomainIdentifier partnerId, DomainIdentifier organizationId, LocalDate effectiveOn) {
        Partner partner = requirePartner(partnerId, organizationId, effectiveOn);
        if (!partner.roles().contains(PartnerRole.SUPPLIER) && !partner.roles().contains(PartnerRole.MANUFACTURER)) {
            throw new AssetConflictException(
                    "ITAM_ASSET_ACQUISITION_PARTNER_INVALID", "acquisition partner must be a supplier or manufacturer");
        }
    }

    @Override
    public void validateProducerPartner(
            DomainIdentifier partnerId, DomainIdentifier organizationId, AssetType assetType, LocalDate effectiveOn) {
        Partner partner = requirePartner(partnerId, organizationId, effectiveOn);
        PartnerRole required = assetType == AssetType.HARDWARE ? PartnerRole.MANUFACTURER : PartnerRole.SOFTWARE_PUBLISHER;
        if (!partner.roles().contains(required)) {
            throw new AssetConflictException(
                    "ITAM_ASSET_PRODUCER_INVALID",
                    assetType == AssetType.HARDWARE
                            ? "hardware producer must be an authorized manufacturer"
                            : "software producer must be an authorized software publisher");
        }
    }

    @Override
    public void validateCustodian(
            AssetCustodian custodian, DomainIdentifier organizationId, LocalDate effectiveOn, boolean maintenance) {
        Objects.requireNonNull(custodian, "custodian");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(effectiveOn, "effectiveOn");
        switch (custodian.kind()) {
            case NONE -> { }
            case ORGANIZATION -> {
                if (!organizationId.equals(custodian.referenceId())) {
                    throw new AssetConflictException(
                            "ITAM_ASSET_CUSTODIAN_SCOPE_MISMATCH", "custodian organization differs from asset owner");
                }
                requireOrganization(organizationId);
            }
            case SUBDIVISION -> validateSubdivision(organizationId, custodian.referenceId());
            case ACTOR -> {
                // Actor identifiers are IAM weak references. Authorization at the API/CLI boundary proves the caller;
                // PGM-03 currently exposes no public actor-query port that ITAM may own or bypass.
            }
            case PARTNER -> {
                Partner partner = requirePartner(custodian.referenceId(), organizationId, effectiveOn);
                if (maintenance && !partner.roles().contains(PartnerRole.THIRD_PARTY_SUPPORT_PROVIDER)
                        && !partner.roles().contains(PartnerRole.MANUFACTURER)) {
                    throw new AssetConflictException(
                            "ITAM_ASSET_MAINTENANCE_PARTNER_INVALID",
                            "maintenance custodian must be a support provider or manufacturer");
                }
            }
        }
    }

    private void requireOrganization(DomainIdentifier organizationId) {
        if (organizations.findById(organizationId).filter(value -> value.state() != OrganizationState.DELETED).isEmpty()) {
            throw new AssetConflictException("ITAM_ASSET_ORGANIZATION_INVALID", "owning organization does not exist");
        }
    }

    private Partner requirePartner(DomainIdentifier partnerId, DomainIdentifier organizationId, LocalDate effectiveOn) {
        Partner partner = partners.get(Objects.requireNonNull(partnerId, "partnerId"));
        if (!partner.governingOrganizationId().equals(organizationId)) {
            throw new AssetConflictException("ITAM_ASSET_PARTNER_SCOPE_MISMATCH", "partner belongs to another organization");
        }
        if (!partner.selectableOn(effectiveOn)) {
            throw new AssetConflictException("ITAM_ASSET_PARTNER_NOT_SELECTABLE", "partner is not active on the effective date");
        }
        return partner;
    }
}
