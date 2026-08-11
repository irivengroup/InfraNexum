package io.infranexum.core.entitlements;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Exact UTC policy for the 180-day Lite evaluation and 30-day conversion window. */
public final class LiteEvaluationPolicy {
    public static final long EVALUATION_DAYS = 180;
    public static final long CONVERSION_DAYS = 30;

    public LiteEvaluation evaluate(Instant evaluationStartedAt, Instant now) {
        Objects.requireNonNull(evaluationStartedAt, "evaluationStartedAt");
        Objects.requireNonNull(now, "now");
        InstallationIdentity.requireWholeSecond(evaluationStartedAt, "evaluationStartedAt");
        InstallationIdentity.requireWholeSecond(now, "now");
        if (now.isBefore(evaluationStartedAt)) {
            throw new ClockRollbackException("current time precedes the Lite evaluation origin");
        }
        Instant conversionAt = evaluationStartedAt.plus(EVALUATION_DAYS, ChronoUnit.DAYS);
        Instant hardStopAt = conversionAt.plus(CONVERSION_DAYS, ChronoUnit.DAYS);
        LiteUsageState state = now.isBefore(conversionAt)
                ? LiteUsageState.EVALUATION
                : now.isBefore(hardStopAt) ? LiteUsageState.CONVERSION_REQUIRED : LiteUsageState.HARD_STOPPED;
        return new LiteEvaluation(state, evaluationStartedAt, conversionAt, hardStopAt, now);
    }
}
