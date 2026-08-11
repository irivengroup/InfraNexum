package io.infranexum.server.workers;

import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.workers.WorkerPoolConfiguration;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated Server configuration for the bounded background-worker runtime. */
@Validated
@ConfigurationProperties(prefix = "infranexum.workers")
public record WorkerRuntimeProperties(
        boolean enabled,
        int concurrency,
        Duration pollInterval,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration shutdownTimeout,
        int maximumAttempts,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        double jitterRatio) {

    public WorkerRuntimeProperties {
        // Reuse the core value object and retry policy as the single invariant source.
        new WorkerPoolConfiguration(
                concurrency, pollInterval, leaseDuration, heartbeatInterval, shutdownTimeout);
        new ExponentialBackoffPolicy(
                maximumAttempts,
                initialRetryDelay,
                maximumRetryDelay,
                jitterRatio,
                () -> 0.0d);
    }

    /** Materializes the immutable core worker-pool configuration. */
    public WorkerPoolConfiguration poolConfiguration() {
        return new WorkerPoolConfiguration(
                concurrency, pollInterval, leaseDuration, heartbeatInterval, shutdownTimeout);
    }

    /** Creates the bounded retry policy used by task claims and failure transitions. */
    public RetryPolicy retryPolicy() {
        return new ExponentialBackoffPolicy(
                maximumAttempts,
                initialRetryDelay,
                maximumRetryDelay,
                jitterRatio,
                () -> ThreadLocalRandom.current().nextDouble());
    }
}
