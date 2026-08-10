package io.infranexum.core.workers;

import java.time.Duration;
import java.util.Objects;

/** Observable result of a bounded worker-pool shutdown attempt. */
public record ShutdownReport(
        boolean graceful,
        boolean forced,
        boolean terminated,
        int configuredWorkers,
        Duration elapsed) {
    public ShutdownReport {
        if (graceful == forced) {
            throw new IllegalArgumentException("shutdown must be either graceful or forced");
        }
        if (graceful && !terminated) {
            throw new IllegalArgumentException("graceful shutdown must terminate all workers");
        }
        if (configuredWorkers < 1) {
            throw new IllegalArgumentException("configuredWorkers must be positive");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
