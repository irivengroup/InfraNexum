package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authoritative, secret-free runtime decision for service and mutation guards. */
public record EntitlementRuntimeStatus(
        DomainIdentifier installationId,
        InstallationProfile profile,
        AllocationTier allocationTier,
        EntitlementRuntimePhase phase,
        Instant evaluatedAt,
        Instant evaluationStartedAt,
        Instant conversionRequiredAt,
        Instant hardStopAt,
        Instant validUntil,
        Instant graceUntil,
        long acceptedSequence,
        DomainIdentifier acceptedActivationId,
        Set<String> entitledCapabilities,
        Map<String, Long> quotaOverrides,
        boolean serviceStartupPermitted,
        boolean mutationPermitted) {
    public EntitlementRuntimeStatus {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(allocationTier, "allocationTier");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (acceptedSequence < 0) {
            throw new IllegalArgumentException("acceptedSequence must be non-negative");
        }
        if ((acceptedSequence == 0) != (acceptedActivationId == null)) {
            throw new IllegalArgumentException("accepted activation identity must match sequence presence");
        }
        entitledCapabilities = Set.copyOf(Objects.requireNonNull(entitledCapabilities, "entitledCapabilities"));
        quotaOverrides = Map.copyOf(Objects.requireNonNull(quotaOverrides, "quotaOverrides"));
        if (entitledCapabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("entitledCapabilities must contain non-blank values");
        }
        if (quotaOverrides.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("quotaOverrides must contain named non-negative values");
        }
    }

    public static EntitlementRuntimeStatus from(
            InstallationIdentity identity, AllocationTier tier, LiteEvaluation evaluation) {
        return new EntitlementRuntimeStatus(
                identity.installationId(), InstallationProfile.LITE, tier,
                switch (evaluation.state()) {
                    case EVALUATION -> EntitlementRuntimePhase.EVALUATION;
                    case CONVERSION_REQUIRED -> EntitlementRuntimePhase.CONVERSION_REQUIRED;
                    case HARD_STOPPED -> EntitlementRuntimePhase.HARD_STOPPED;
                    case MIGRATED -> throw new IllegalArgumentException("migrated Lite state requires a paid profile");
                },
                evaluation.evaluatedAt(), evaluation.evaluationStartedAt(),
                evaluation.conversionRequiredAt(), evaluation.hardStopAt(),
                null, null, 0, null, Set.of(), Map.of(),
                evaluation.permitsServiceStartup(), evaluation.permitsMutation());
    }

    public static EntitlementRuntimeStatus from(
            InstallationIdentity identity, ActivationVerificationResult result, Instant evaluatedAt) {
        ActivationManifestPayload payload = result.payload();
        return new EntitlementRuntimeStatus(
                identity.installationId(), payload.profile(), payload.allocationTier(),
                switch (result.state()) {
                    case ACTIVE -> EntitlementRuntimePhase.ACTIVE;
                    case GRACE -> EntitlementRuntimePhase.GRACE;
                    case HARD_STOPPED -> EntitlementRuntimePhase.HARD_STOPPED;
                },
                Objects.requireNonNull(evaluatedAt, "evaluatedAt"), null, null, null,
                payload.validUntil(), result.graceUntil(), payload.sequence(), payload.activationId(),
                payload.capabilities(), payload.quotas(),
                result.permitsServiceStartup(), result.permitsMutation());
    }
}
