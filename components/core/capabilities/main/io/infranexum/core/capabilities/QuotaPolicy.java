package io.infranexum.core.capabilities;

import java.util.Objects;

/** Stateless quota evaluator used by domain guards before any augmentative mutation. */
public final class QuotaPolicy {
    public QuotaDecision evaluate(
            QuotaAllocationPlan plan, String quotaKey, long currentConsumption, long requestedIncrease) {
        Objects.requireNonNull(plan, "plan");
        if (currentConsumption < 0 || requestedIncrease < 0) {
            throw new IllegalArgumentException("consumption and requested increase must be non-negative");
        }
        long limit = plan.limit(quotaKey);
        long projected;
        try {
            projected = Math.addExact(currentConsumption, requestedIncrease);
        } catch (ArithmeticException error) {
            return new QuotaDecision(
                    quotaKey, limit, currentConsumption, requestedIncrease, Long.MAX_VALUE,
                    false, QuotaUsageLevel.EXCEEDED, "QUOTA_ARITHMETIC_OVERFLOW");
        }
        boolean allowed = requestedIncrease == 0 || (currentConsumption < limit && projected <= limit);
        QuotaUsageLevel level = classify(projected, limit);
        String reason = allowed ? "QUOTA_ALLOCATION_ALLOWED" : "QUOTA_LIMIT_EXCEEDED";
        return new QuotaDecision(
                quotaKey, limit, currentConsumption, requestedIncrease, projected, allowed, level, reason);
    }

    private static QuotaUsageLevel classify(long consumption, long limit) {
        if (limit == 0) {
            return consumption == 0 ? QuotaUsageLevel.EXHAUSTED : QuotaUsageLevel.EXCEEDED;
        }
        if (consumption > limit) {
            return QuotaUsageLevel.EXCEEDED;
        }
        if (consumption == limit) {
            return QuotaUsageLevel.EXHAUSTED;
        }
        // Compare against ceil(90%/80%) without multiplying consumption, so valid
        // long-range quotas cannot overflow while computing utilization thresholds.
        long warningThreshold = limit - Math.floorDiv(limit, 10L);
        if (consumption >= warningThreshold) {
            return QuotaUsageLevel.WARNING;
        }
        long informationThreshold = limit - Math.floorDiv(limit, 5L);
        if (consumption >= informationThreshold) {
            return QuotaUsageLevel.INFORMATION;
        }
        return QuotaUsageLevel.NORMAL;
    }
}
