package io.infranexum.core.capabilities;

import java.util.Objects;
import java.util.Set;

/** Immutable capability requirements loaded from the embedded catalogue. */
public record CapabilityDefinition(
        CapabilityCode code,
        Set<InstallationProfile> allowedProfiles,
        Set<DeploymentRole> requiredRoles,
        Set<InstallationTopology> allowedTopologies,
        Set<TechnicalTrait> requiredTraits,
        boolean activationProtected) {
    public CapabilityDefinition {
        Objects.requireNonNull(code, "code");
        allowedProfiles = Set.copyOf(Objects.requireNonNull(allowedProfiles, "allowedProfiles"));
        requiredRoles = Set.copyOf(Objects.requireNonNull(requiredRoles, "requiredRoles"));
        allowedTopologies = Set.copyOf(Objects.requireNonNull(allowedTopologies, "allowedTopologies"));
        requiredTraits = Set.copyOf(Objects.requireNonNull(requiredTraits, "requiredTraits"));
        if (allowedProfiles.isEmpty()) {
            throw new IllegalArgumentException("allowedProfiles must not be empty");
        }
        if (allowedTopologies.isEmpty()) {
            throw new IllegalArgumentException("allowedTopologies must not be empty");
        }
    }
}
