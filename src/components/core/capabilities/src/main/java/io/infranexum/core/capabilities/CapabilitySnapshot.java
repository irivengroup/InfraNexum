package io.infranexum.core.capabilities;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Deterministic registry snapshot with a stable hash for cache coherence. */
public record CapabilitySnapshot(
        String catalogVersion,
        long profileVersion,
        String capabilityHash,
        Instant evaluatedAt,
        Map<CapabilityCode, CapabilityDecision> decisions) {
    public CapabilitySnapshot {
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        Objects.requireNonNull(capabilityHash, "capabilityHash");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        decisions = Map.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (catalogVersion.isBlank() || profileVersion < 1 || !capabilityHash.matches("[0-9a-f]{64}")
                || decisions.isEmpty()) {
            throw new IllegalArgumentException("invalid capability snapshot");
        }
    }

    public CapabilityDecision require(CapabilityCode code) {
        CapabilityDecision decision = decisions.get(Objects.requireNonNull(code, "code"));
        if (decision == null) {
            throw new IllegalArgumentException("capability is absent from snapshot: " + code);
        }
        return decision;
    }
}
