package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import java.time.Instant;

/** Secret-free representation of the normative Lite/activation lifecycle status endpoint. */
public record EvaluationStatusResponse(
        String installationId,
        String profile,
        String allocationTier,
        String phase,
        Instant evaluatedAt,
        Instant evaluationStartedAt,
        Instant conversionRequiredAt,
        Instant hardStopAt,
        Instant validUntil,
        Instant graceUntil,
        long acceptedSequence,
        String acceptedActivationId,
        boolean serviceStartupPermitted,
        boolean mutationPermitted) {

    public static EvaluationStatusResponse from(EntitlementRuntimeStatus status) {
        return new EvaluationStatusResponse(
                status.installationId().toString(),
                status.profile().name(),
                status.allocationTier().name(),
                status.phase().name(),
                status.evaluatedAt(),
                status.evaluationStartedAt(),
                status.conversionRequiredAt(),
                status.hardStopAt(),
                status.validUntil(),
                status.graceUntil(),
                status.acceptedSequence(),
                status.acceptedActivationId() == null ? null : status.acceptedActivationId().toString(),
                status.serviceStartupPermitted(),
                status.mutationPermitted());
    }
}
