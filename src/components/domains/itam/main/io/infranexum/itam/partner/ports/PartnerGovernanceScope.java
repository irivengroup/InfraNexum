package io.infranexum.itam.partner.ports;

import io.infranexum.core.contracts.DomainIdentifier;

/** Weak-reference validation port preserving Organization/ITAM storage ownership. */
public interface PartnerGovernanceScope {
    boolean organizationExists(DomainIdentifier organizationId);
    boolean subdivisionExists(DomainIdentifier organizationId, DomainIdentifier subdivisionId);
}
