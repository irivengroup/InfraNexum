package io.infranexum.identity.access.domain;

/** Immutable policy-version lifecycle from draft through retirement. */
public enum PolicyState {
    DRAFT,
    VALIDATED,
    APPROVED,
    ACTIVE,
    DEPRECATED,
    RETIRED
}
