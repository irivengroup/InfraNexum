package io.infranexum.core.entitlements;

/** Unified runtime phases exposed to startup, HTTP and operational diagnostics. */
public enum EntitlementRuntimePhase {
    EVALUATION,
    CONVERSION_REQUIRED,
    ACTIVE,
    GRACE,
    HARD_STOPPED
}
