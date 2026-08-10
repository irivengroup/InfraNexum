package io.infranexum.core.workers;

import java.time.Duration;
import java.util.Objects;

/** Bounded worker-pool timing and concurrency configuration. */
public record WorkerPoolConfiguration(
        int concurrency,
        Duration pollInterval,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration shutdownTimeout) {
    private static final Duration MAX_DURATION = Duration.ofDays(30);
    public WorkerPoolConfiguration {
        if (concurrency < 1 || concurrency > 256) {
            throw new IllegalArgumentException("concurrency must be between 1 and 256");
        }
        pollInterval = positive(pollInterval, "pollInterval");
        leaseDuration = positive(leaseDuration, "leaseDuration");
        heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        shutdownTimeout = positive(shutdownTimeout, "shutdownTimeout");
        if (heartbeatInterval.compareTo(leaseDuration.dividedBy(2)) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be less than half the leaseDuration");
        }
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        if (value.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException(field + " must not exceed 30 days");
        }
        return value;
    }
}
