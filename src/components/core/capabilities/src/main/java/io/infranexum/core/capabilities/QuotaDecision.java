package io.infranexum.core.capabilities;

import java.util.Objects;

/** Explainable allocation decision; existing objects are never deleted by this result. */
public record QuotaDecision(
        String quotaKey,
        long limit,
        long currentConsumption,
        long requestedIncrease,
        long projectedConsumption,
        boolean allowed,
        QuotaUsageLevel usageLevel,
        String reasonCode) {
    public QuotaDecision {
        Objects.requireNonNull(quotaKey, "quotaKey");
        Objects.requireNonNull(usageLevel, "usageLevel");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (quotaKey.isBlank() || limit < 0 || currentConsumption < 0 || requestedIncrease < 0
                || projectedConsumption < 0) {
            throw new IllegalArgumentException("invalid quota decision values");
        }
    }
}
