package io.infranexum.server.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerRuntimePropertiesTest {
    @Test
    void materializesCorePoolAndRetryConfiguration() {
        WorkerRuntimeProperties properties = valid();

        assertTrue(properties.enabled());
        assertEquals(2, properties.poolConfiguration().concurrency());
        assertEquals(5, properties.retryPolicy().maximumAttempts());
        Duration delay = properties.retryPolicy().delayAfterFailure(1);
        assertTrue(!delay.isNegative() && delay.compareTo(Duration.ofSeconds(2)) <= 0);
    }

    @Test
    void rejectsInvalidPoolAndRetryInvariantsAtConstructionTime() {
        WorkerRuntimeProperties base = valid();
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntimeProperties(
                true, 0, base.pollInterval(), base.leaseDuration(), base.heartbeatInterval(),
                base.shutdownTimeout(), base.maximumAttempts(), base.initialRetryDelay(),
                base.maximumRetryDelay(), base.jitterRatio()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntimeProperties(
                true, base.concurrency(), base.pollInterval(), base.leaseDuration(), Duration.ofSeconds(15),
                base.shutdownTimeout(), base.maximumAttempts(), base.initialRetryDelay(),
                base.maximumRetryDelay(), base.jitterRatio()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntimeProperties(
                true, base.concurrency(), base.pollInterval(), base.leaseDuration(), base.heartbeatInterval(),
                base.shutdownTimeout(), 0, base.initialRetryDelay(), base.maximumRetryDelay(), base.jitterRatio()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntimeProperties(
                true, base.concurrency(), base.pollInterval(), base.leaseDuration(), base.heartbeatInterval(),
                base.shutdownTimeout(), base.maximumAttempts(), Duration.ofSeconds(2), Duration.ofSeconds(1),
                base.jitterRatio()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntimeProperties(
                true, base.concurrency(), base.pollInterval(), base.leaseDuration(), base.heartbeatInterval(),
                base.shutdownTimeout(), base.maximumAttempts(), base.initialRetryDelay(),
                base.maximumRetryDelay(), Double.NaN));
    }

    static WorkerRuntimeProperties valid() {
        return new WorkerRuntimeProperties(
                true,
                2,
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                5,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                0.2d);
    }
}
