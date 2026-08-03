package io.infranexum.server.platform;

import io.infranexum.core.capabilities.QuotaAllocationPlan;
import java.util.Map;

/** Effective secret-free quota allocation for the current installation. */
public record QuotaPlanResponse(
        String catalogVersion,
        String profile,
        String allocationTier,
        Map<String, Long> quotas) {
    static QuotaPlanResponse from(QuotaAllocationPlan plan) {
        return new QuotaPlanResponse(
                plan.catalogVersion(), plan.profile().name(), plan.tier().name(), new java.util.TreeMap<>(plan.limits()));
    }
}
