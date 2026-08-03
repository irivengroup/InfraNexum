package io.infranexum.core.capabilities;

/** Activation state supplied by Core Entitlements after signature and lifecycle evaluation. */
public enum ActivationState {
    NOT_REQUIRED,
    ACTIVE,
    GRACE,
    LOCKED,
    INVALID;

    public boolean permitsProtectedCapabilities() {
        return this == NOT_REQUIRED || this == ACTIVE || this == GRACE;
    }
}
