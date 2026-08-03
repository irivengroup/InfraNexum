package io.infranexum.core.capabilities;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated technical and commercial inputs used by the authoritative registry. */
public record CapabilityEnvironment(
        InstallationProfile profile,
        AllocationTier allocationTier,
        InstallationTopology topology,
        Set<DeploymentRole> roles,
        Set<TechnicalTrait> traits,
        Set<CapabilityCode> installedCapabilities,
        Map<CapabilityCode, DependencyStatus> dependencyStatus,
        Set<CapabilityCode> entitledCapabilities,
        ActivationState activationState,
        String catalogVersion,
        long profileVersion) {
    public CapabilityEnvironment {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(allocationTier, "allocationTier");
        Objects.requireNonNull(topology, "topology");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        traits = Set.copyOf(Objects.requireNonNull(traits, "traits"));
        installedCapabilities = Set.copyOf(Objects.requireNonNull(installedCapabilities, "installedCapabilities"));
        dependencyStatus = Map.copyOf(Objects.requireNonNull(dependencyStatus, "dependencyStatus"));
        entitledCapabilities = Set.copyOf(Objects.requireNonNull(entitledCapabilities, "entitledCapabilities"));
        Objects.requireNonNull(activationState, "activationState");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        if (profileVersion < 1) {
            throw new IllegalArgumentException("profileVersion must be positive");
        }
        if (!roles.contains(DeploymentRole.SERVER)) {
            throw new IllegalArgumentException("Capability Registry authority requires the server role");
        }
        validateProfileTier(profile, allocationTier);
        validateTopology(profile, topology);
        if (profile != InstallationProfile.ENTERPRISE && roles.contains(DeploymentRole.AGENT)) {
            throw new IllegalArgumentException("agent role is Enterprise-only");
        }
        if (profile != InstallationProfile.ENTERPRISE && traits.contains(TechnicalTrait.ORACLE_BACKEND)) {
            throw new IllegalArgumentException("oracle-backend trait is Enterprise-only");
        }
        if (profile == InstallationProfile.LITE && activationState != ActivationState.NOT_REQUIRED) {
            throw new IllegalArgumentException("Lite activation state must be NOT_REQUIRED");
        }
        if (profile != InstallationProfile.LITE && activationState == ActivationState.NOT_REQUIRED) {
            throw new IllegalArgumentException("Pro and Enterprise require an activation state");
        }
    }

    public DependencyStatus dependencyFor(CapabilityCode code) {
        return dependencyStatus.getOrDefault(code, DependencyStatus.NOT_APPLICABLE);
    }

    private static void validateProfileTier(InstallationProfile profile, AllocationTier tier) {
        boolean valid = switch (profile) {
            case LITE -> tier == AllocationTier.STANDARD;
            case PRO -> tier == AllocationTier.STANDARD || tier == AllocationTier.ADVANCED;
            case ENTERPRISE -> tier == AllocationTier.STANDARD || tier == AllocationTier.ULTIMATE;
        };
        if (!valid) {
            throw new IllegalArgumentException("allocation tier is incompatible with profile");
        }
    }

    private static void validateTopology(InstallationProfile profile, InstallationTopology topology) {
        boolean valid = switch (profile) {
            case LITE -> topology == InstallationTopology.SINGLE_NODE;
            case PRO -> topology == InstallationTopology.SINGLE_NODE
                    || topology == InstallationTopology.SPLIT_WEB
                    || topology == InstallationTopology.HIGH_AVAILABILITY;
            case ENTERPRISE -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("topology is incompatible with profile");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
