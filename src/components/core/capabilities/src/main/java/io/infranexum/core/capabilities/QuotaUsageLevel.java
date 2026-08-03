package io.infranexum.core.capabilities;

/** Published utilization thresholds: 80% information, 90% warning, 100% exhausted. */
public enum QuotaUsageLevel {
    NORMAL,
    INFORMATION,
    WARNING,
    EXHAUSTED,
    EXCEEDED
}
