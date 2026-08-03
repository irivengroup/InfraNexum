package io.infranexum.core.capabilities;

/** Stable public reasons returned by capability decisions. */
public enum CapabilityReasonCode {
    AVAILABLE,
    CAPABILITY_UNKNOWN,
    PROFILE_CAPABILITY_NOT_INSTALLED,
    ROLE_NOT_DEPLOYED,
    TOPOLOGY_UNSUPPORTED,
    TRAIT_REQUIRED,
    DEPENDENCY_UNAVAILABLE,
    ACTIVATION_REQUIRED,
    ENTITLEMENT_NOT_GRANTED,
    CONFIGURATION_INVALID,
    PROFILE_MIGRATION_REQUIRED
}
