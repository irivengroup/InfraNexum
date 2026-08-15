package io.infranexum.identity.access.domain;

/** Normative PDP outcomes; every value except {@link #PERMIT} is denied by a PEP. */
public enum PolicyDecision {
    PERMIT,
    DENY,
    NOT_APPLICABLE,
    INDETERMINATE
}
