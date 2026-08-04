package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Durable entitlement state loaded by runtime composition roots. */
public record EntitlementStateRecord(
        InstallationProfile profile,
        AllocationTier allocationTier,
        Instant evaluationStartedAt,
        Instant lastReliableAt,
        long timeGeneration,
        AcceptedSequence acceptedSequence,
        EntitlementRuntimePhase phase,
        Instant validUntil,
        Instant graceUntil,
        Instant updatedAt) {
    public EntitlementStateRecord {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(allocationTier, "allocationTier");
        Objects.requireNonNull(lastReliableAt, "lastReliableAt");
        Objects.requireNonNull(acceptedSequence, "acceptedSequence");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (timeGeneration < 1) {
            throw new IllegalArgumentException("timeGeneration must be positive");
        }
        if (profile == InstallationProfile.LITE) {
            Objects.requireNonNull(evaluationStartedAt, "evaluationStartedAt");
            if (acceptedSequence.value() != 0 || validUntil != null || graceUntil != null) {
                throw new IllegalArgumentException("Lite state cannot contain an activation manifest");
            }
        } else {
            if (evaluationStartedAt != null || acceptedSequence.value() < 1 || validUntil == null || graceUntil == null) {
                throw new IllegalArgumentException("paid state must contain an accepted activation and validity dates");
            }
        }
    }

    public DomainIdentifier acceptedActivationId() {
        return acceptedSequence.activationId();
    }
}
