package io.infranexum.identity.access.ports;

import io.infranexum.core.contracts.DomainIdentifier;

/**
 * Public-reference validation port used by IAM for Organization-owned identifiers.
 *
 * <p>IAM stores organization and subdivision identifiers as weak references. The owning
 * Organization bounded context remains authoritative and is queried through this port before
 * IAM persists a new external reference.</p>
 */
public interface OrganizationScopeReferencePort {
    boolean organizationExists(DomainIdentifier organizationId);

    boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId);
}
