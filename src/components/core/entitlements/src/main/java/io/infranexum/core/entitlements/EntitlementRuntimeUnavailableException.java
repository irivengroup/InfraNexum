package io.infranexum.core.entitlements;

/** Indicates that no authoritative entitlement decision can currently be served. */
public final class EntitlementRuntimeUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public EntitlementRuntimeUnavailableException(String message) {
        super(message);
    }
}
