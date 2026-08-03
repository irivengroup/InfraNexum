package io.infranexum.core.capabilities;

/** Operational status of a capability-specific technical dependency. */
public enum DependencyStatus {
    NOT_APPLICABLE,
    OPERATIONAL,
    DEGRADED,
    UNAVAILABLE;

    public boolean isUsable() {
        return this == NOT_APPLICABLE || this == OPERATIONAL;
    }
}
