package io.infranexum.server.dcim;

import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.ports.FacilityScopePolicy;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.SubdivisionState;
import java.util.Objects;

/** Validates DCIM weak references against the authoritative Organization bounded context. */
public final class DcimFacilityScopePolicy implements FacilityScopePolicy {
    private final JdbcOrganizationRepository organizations;
    private final JdbcSubdivisionRepository subdivisions;

    public DcimFacilityScopePolicy(JdbcOrganizationRepository organizations, JdbcSubdivisionRepository subdivisions) {
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.subdivisions = Objects.requireNonNull(subdivisions, "subdivisions");
    }

    @Override
    public void requireActiveScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        var organization = organizations.findById(Objects.requireNonNull(organizationId, "organizationId"))
                .orElseThrow(() -> new FacilityConflictException(
                        "DCIM_ORGANIZATION_INVALID", "owning organization does not exist"));
        if (organization.state() != OrganizationState.ACTIVE) {
            throw new FacilityConflictException("DCIM_ORGANIZATION_INACTIVE", "owning organization must be active");
        }
        var subdivision = subdivisions.findById(organizationId, Objects.requireNonNull(subdivisionId, "subdivisionId"))
                .orElseThrow(() -> new FacilityConflictException(
                        "DCIM_SUBDIVISION_INVALID", "owning subdivision does not exist in the organization"));
        if (subdivision.state() != SubdivisionState.ACTIVE) {
            throw new FacilityConflictException("DCIM_SUBDIVISION_INACTIVE", "owning subdivision must be active");
        }
    }
}
