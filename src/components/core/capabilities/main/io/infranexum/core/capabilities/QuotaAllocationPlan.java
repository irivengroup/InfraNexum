package io.infranexum.core.capabilities;

import java.util.Map;
import java.util.Objects;

/** Validated effective quota limits for one installation and allocation tier. */
public record QuotaAllocationPlan(
        String catalogVersion,
        InstallationProfile profile,
        AllocationTier tier,
        Map<String, Long> limits) {
    public QuotaAllocationPlan {
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(tier, "tier");
        limits = Map.copyOf(Objects.requireNonNull(limits, "limits"));
        if (catalogVersion.isBlank() || limits.isEmpty()) {
            throw new IllegalArgumentException("catalogVersion and limits must not be empty");
        }
        if (limits.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("quota limits must be non-negative");
        }
    }

    public long limit(String key) {
        Long value = limits.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            throw new IllegalArgumentException("unknown allocated quota: " + key);
        }
        return value;
    }
}
