package io.infranexum.core.entitlements;

import java.util.Objects;

/** Enforces lifecycle restrictions at service-startup and mutating application boundaries. */
public final class EntitlementGuard {
    public void requireServiceStartup(LiteEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evaluation.permitsServiceStartup()) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.LITE_HARD_STOPPED,
                    "Lite usage reached the hard-stop boundary");
        }
    }

    public void requireMutation(LiteEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (evaluation.permitsMutation()) {
            return;
        }
        if (evaluation.state() == LiteUsageState.CONVERSION_REQUIRED) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.LITE_CONVERSION_REQUIRED,
                    "Lite usage is restricted to read, export, backup, diagnostics and activation operations");
        }
        throw new EntitlementAccessException(
                EntitlementErrorCodes.LITE_HARD_STOPPED,
                "Lite usage reached the hard-stop boundary");
    }

    public void requireServiceStartup(ActivationVerificationResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.permitsServiceStartup()) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.ACTIVATION_EXPIRED,
                    "Activation and its fixed grace period have expired");
        }
    }

    public void requireMutation(ActivationVerificationResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.permitsMutation()) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.ACTIVATION_EXPIRED,
                    "Activation and its fixed grace period have expired");
        }
    }
}
