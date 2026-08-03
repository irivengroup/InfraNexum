package io.infranexum.core.capabilities;

/** Activation state supplied by Core Entitlements; signature verification is a later boundary. */
public enum ActivationState {
    NOT_REQUIRED,
    ACTIVE,
    GRACE,
    LOCKED,
    INVALID;

    public boolean permitsProtectedCapabilities() {
        return this == ACTIVE || this == GRACE;
    }
}
