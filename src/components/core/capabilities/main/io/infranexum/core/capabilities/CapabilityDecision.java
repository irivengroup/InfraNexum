package io.infranexum.core.capabilities;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Explainable authoritative decision consumed by API, CLI, Web, workers and domain guards. */
public record CapabilityDecision(
        CapabilityCode capabilityCode,
        boolean available,
        CapabilityReasonCode reasonCode,
        InstallationProfile profile,
        InstallationTopology topology,
        Set<DeploymentRole> roles,
        Set<TechnicalTrait> traits,
        DependencyStatus dependencyStatus,
        ActivationState activationState,
        String catalogVersion,
        String capabilityHash,
        Instant evaluatedAt,
        long profileVersion) {
    public CapabilityDecision {
        Objects.requireNonNull(capabilityCode, "capabilityCode");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(topology, "topology");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        traits = Set.copyOf(Objects.requireNonNull(traits, "traits"));
        Objects.requireNonNull(dependencyStatus, "dependencyStatus");
        Objects.requireNonNull(activationState, "activationState");
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        Objects.requireNonNull(capabilityHash, "capabilityHash");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (catalogVersion.isBlank() || !capabilityHash.matches("[0-9a-f]{64}") || profileVersion < 1) {
            throw new IllegalArgumentException("invalid capability decision metadata");
        }
        if (available != (reasonCode == CapabilityReasonCode.AVAILABLE)) {
            throw new IllegalArgumentException("available and reasonCode are inconsistent");
        }
    }
}
