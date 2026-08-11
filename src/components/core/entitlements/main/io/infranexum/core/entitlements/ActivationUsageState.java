package io.infranexum.core.entitlements;

/** Runtime state of a cryptographically valid Pro or Enterprise activation. */
public enum ActivationUsageState {
    ACTIVE,
    GRACE,
    HARD_STOPPED
}
