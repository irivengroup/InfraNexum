package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Validated effective rights derived from a signed activation manifest. */
public record ActivationVerificationResult(
        ActivationUsageState state,
        ActivationManifestPayload payload,
        QuotaAllocationPlan quotaPlan,
        Set<String> entitledCapabilities,
        Instant graceUntil) {
    public ActivationVerificationResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(quotaPlan, "quotaPlan");
        entitledCapabilities = Set.copyOf(Objects.requireNonNull(entitledCapabilities, "entitledCapabilities"));
        Objects.requireNonNull(graceUntil, "graceUntil");
    }

    public ActivationState capabilityActivationState() {
        return switch (state) {
            case ACTIVE -> ActivationState.ACTIVE;
            case GRACE -> ActivationState.GRACE;
            case HARD_STOPPED -> ActivationState.LOCKED;
        };
    }

    public boolean permitsServiceStartup() {
        return state != ActivationUsageState.HARD_STOPPED;
    }

    public boolean permitsMutation() {
        return state == ActivationUsageState.ACTIVE || state == ActivationUsageState.GRACE;
    }
}
