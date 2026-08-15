package io.infranexum.server.itam;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.ports.PartnerGovernanceScope;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import java.util.Objects;

/** Validates ITAM weak references against the authoritative Organization context. */
final class JdbcPartnerGovernanceScope implements PartnerGovernanceScope {
    private final OrganizationRepository organizations;
    private final SubdivisionRepository subdivisions;

    JdbcPartnerGovernanceScope(OrganizationRepository organizations, SubdivisionRepository subdivisions) {
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.subdivisions = Objects.requireNonNull(subdivisions, "subdivisions");
    }

    @Override
    public boolean organizationExists(DomainIdentifier organizationId) {
        return organizations.findById(Objects.requireNonNull(organizationId, "organizationId"))
                .filter(value -> value.state() != OrganizationState.DELETED)
                .isPresent();
    }

    @Override
    public boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        return subdivisions.findById(Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(subdivisionId, "subdivisionId"))
                .filter(value -> value.state() != SubdivisionState.DELETED)
                .isPresent();
    }
}
