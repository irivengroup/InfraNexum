package io.infranexum.core.capabilities;

/** Defense-in-depth guard used by use cases and domain services. */
public final class CapabilityGuard {
    private CapabilityGuard() {}

    public static void requireAvailable(CapabilityDecision decision) {
        if (!java.util.Objects.requireNonNull(decision, "decision").available()) {
            throw new CapabilityUnavailableException(decision);
        }
    }
}
