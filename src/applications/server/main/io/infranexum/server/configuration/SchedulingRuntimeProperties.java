package io.infranexum.server.configuration;

import io.infranexum.core.contracts.ConfigurationException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated configuration for Spring-managed scheduled application work. */
@Validated
@ConfigurationProperties(prefix = "infranexum.scheduling")
public record SchedulingRuntimeProperties(int poolSize, Duration shutdownTimeout) {
    private static final int MAXIMUM_POOL_SIZE = 32;
    private static final Duration MAXIMUM_SHUTDOWN_TIMEOUT = Duration.ofMinutes(5);

    public SchedulingRuntimeProperties {
        if (poolSize < 1 || poolSize > MAXIMUM_POOL_SIZE) {
            throw new ConfigurationException(
                    "scheduling poolSize must be between 1 and " + MAXIMUM_POOL_SIZE);
        }
        if (shutdownTimeout == null
                || shutdownTimeout.isZero()
                || shutdownTimeout.isNegative()
                || shutdownTimeout.compareTo(MAXIMUM_SHUTDOWN_TIMEOUT) > 0) {
            throw new ConfigurationException(
                    "scheduling shutdownTimeout must be > PT0S and <= " + MAXIMUM_SHUTDOWN_TIMEOUT);
        }
    }
}
