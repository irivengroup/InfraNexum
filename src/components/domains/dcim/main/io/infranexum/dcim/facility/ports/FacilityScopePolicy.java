package io.infranexum.dcim.facility.ports;

import io.infranexum.core.contracts.DomainIdentifier;

/** Weak-reference validation against Organization/Subdivision authority. */
public interface FacilityScopePolicy {
    void requireActiveScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId);
}
