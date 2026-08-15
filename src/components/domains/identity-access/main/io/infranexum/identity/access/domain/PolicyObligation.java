package io.infranexum.identity.access.domain;

/** Obligations a PEP must satisfy before a permit decision can reach a resource. */
public enum PolicyObligation {
    REQUIRE_JUSTIFICATION,
    STEP_UP_MFA,
    REQUIRE_APPROVAL,
    MASK_FIELDS,
    LIMIT_FIELDS
}
