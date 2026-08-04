package io.infranexum.server.platform;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.DependencyStatus;
import io.infranexum.core.capabilities.DeploymentRole;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.InstallationTopology;
import io.infranexum.core.capabilities.TechnicalTrait;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Startup inputs for the authoritative Capability Registry. */
@Validated
@ConfigurationProperties(prefix = "infranexum.platform")
public record PlatformCapabilityProperties(
        @NotNull InstallationProfile profile,
        @NotNull AllocationTier allocationTier,
        @NotNull InstallationTopology topology,
        @NotEmpty Set<DeploymentRole> roles,
        Set<TechnicalTrait> traits,
        @NotEmpty Set<String> installedCapabilities,
        Set<String> entitledCapabilities,
        Map<String, DependencyStatus> dependencies,
        @NotNull ActivationState activationState,
        @NotBlank String catalogVersion,
        @Min(1) long profileVersion,
        Map<String, Long> quotaOverrides) {

    public PlatformCapabilityProperties {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(allocationTier, "allocationTier");
        Objects.requireNonNull(topology, "topology");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        traits = Set.copyOf(Objects.requireNonNullElse(traits, Set.of()));
        installedCapabilities = Set.copyOf(Objects.requireNonNull(installedCapabilities, "installedCapabilities"));
        entitledCapabilities = Set.copyOf(Objects.requireNonNullElse(entitledCapabilities, Set.of()));
        dependencies = Map.copyOf(Objects.requireNonNullElse(dependencies, Map.of()));
        Objects.requireNonNull(activationState, "activationState");
        catalogVersion = Objects.requireNonNull(catalogVersion, "catalogVersion").strip();
        quotaOverrides = Map.copyOf(Objects.requireNonNullElse(quotaOverrides, Map.of()));
        if (roles.isEmpty() || installedCapabilities.isEmpty() || catalogVersion.isEmpty() || profileVersion < 1) {
            throw new IllegalArgumentException("platform capability configuration is incomplete");
        }
        if (quotaOverrides.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("quota overrides must be non-negative");
        }
    }

    CapabilityEnvironment toEnvironment() {
        return toEnvironment(profile, allocationTier, entitledCapabilities, activationState);
    }

    CapabilityEnvironment toEnvironment(
            InstallationProfile effectiveProfile,
            AllocationTier effectiveTier,
            Set<String> effectiveEntitlements,
            ActivationState effectiveActivationState) {
        Set<CapabilityCode> installed = installedCapabilities.stream()
                .map(CapabilityCode::new)
                .collect(Collectors.toUnmodifiableSet());
        Set<CapabilityCode> entitled = Objects.requireNonNull(effectiveEntitlements, "effectiveEntitlements").stream()
                .map(CapabilityCode::new)
                .collect(Collectors.toUnmodifiableSet());
        Map<CapabilityCode, DependencyStatus> statuses = dependencies.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(entry -> new CapabilityCode(entry.getKey()), Map.Entry::getValue));
        return new CapabilityEnvironment(
                Objects.requireNonNull(effectiveProfile, "effectiveProfile"),
                Objects.requireNonNull(effectiveTier, "effectiveTier"),
                topology,
                roles,
                traits,
                installed,
                statuses,
                entitled,
                Objects.requireNonNull(effectiveActivationState, "effectiveActivationState"),
                catalogVersion,
                profileVersion);
    }
}
