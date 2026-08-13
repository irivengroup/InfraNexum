package io.infranexum.server.identityaccess;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.ports.OrganizationRepository;
import io.infranexum.organization.ports.SubdivisionRepository;
import java.util.Objects;

/**
 * IAM outbound adapter for weak Organization/Subdivision references.
 *
 * <p>The adapter validates references through the Organization context's public repository ports;
 * IAM never declares a physical foreign key to Organization-owned tables.</p>
 */
public final class OrganizationScopeReferenceAdapter implements OrganizationScopeReferencePort {
    private final OrganizationRepository organizations;
    private final SubdivisionRepository subdivisions;

    public OrganizationScopeReferenceAdapter(
            OrganizationRepository organizations, SubdivisionRepository subdivisions) {
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.subdivisions = Objects.requireNonNull(subdivisions, "subdivisions");
    }

    @Override
    public boolean organizationExists(DomainIdentifier organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        return organizations.findById(organizationId)
                .filter(organization -> organization.state() != OrganizationState.DELETED)
                .isPresent();
    }

    @Override
    public boolean subdivisionExists(
            DomainIdentifier organizationId, DomainIdentifier subdivisionId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subdivisionId, "subdivisionId");
        return subdivisions.findById(organizationId, subdivisionId)
                .filter(subdivision -> subdivision.state() != SubdivisionState.DELETED)
                .isPresent();
    }
}
