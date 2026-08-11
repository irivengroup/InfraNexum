package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.contracts.DomainErrorCode;
import java.time.Instant;
import java.util.Objects;

/** Effective Lite state and exact UTC boundaries. */
public record LiteEvaluation(
        LiteUsageState state,
        Instant evaluationStartedAt,
        Instant conversionRequiredAt,
        Instant hardStopAt,
        Instant evaluatedAt) {
    public LiteEvaluation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evaluationStartedAt, "evaluationStartedAt");
        Objects.requireNonNull(conversionRequiredAt, "conversionRequiredAt");
        Objects.requireNonNull(hardStopAt, "hardStopAt");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    public boolean permitsServiceStartup() {
        return state == LiteUsageState.EVALUATION || state == LiteUsageState.CONVERSION_REQUIRED;
    }

    public boolean permitsMutation() {
        return state == LiteUsageState.EVALUATION;
    }

    public ActivationState capabilityActivationState() {
        return state == LiteUsageState.HARD_STOPPED ? ActivationState.LOCKED : ActivationState.NOT_REQUIRED;
    }

    public DomainErrorCode mutationFailureCode() {
        if (state == LiteUsageState.CONVERSION_REQUIRED) {
            return EntitlementErrorCodes.LITE_CONVERSION_REQUIRED;
        }
        if (state == LiteUsageState.HARD_STOPPED) {
            return EntitlementErrorCodes.LITE_HARD_STOPPED;
        }
        return null;
    }
}
