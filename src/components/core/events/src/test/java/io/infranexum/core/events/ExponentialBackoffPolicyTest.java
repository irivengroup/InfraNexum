package io.infranexum.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExponentialBackoffPolicyTest {
    @Test
    void appliesBoundedExponentialDelayAndJitter() {
        RetryPolicy policy = new ExponentialBackoffPolicy(
                5, Duration.ofMillis(100), Duration.ofMillis(500), 0.5, () -> 1.0);
        assertEquals(Duration.ofMillis(150), policy.delayAfterFailure(1));
        assertEquals(Duration.ofMillis(300), policy.delayAfterFailure(2));
        assertEquals(Duration.ofMillis(500), policy.delayAfterFailure(9));
        assertEquals(5, policy.maximumAttempts());
    }

    @Test
    void rejectsUnsafeConfigurationAndInvalidJitterSource() {
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                0, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                1, Duration.ZERO, Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                1, Duration.ofSeconds(2), Duration.ofSeconds(1), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoffPolicy(
                1, Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0, () -> 0.0));
        RetryPolicy invalidSource = new ExponentialBackoffPolicy(
                1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> -1.0);
        assertThrows(IllegalStateException.class, () -> invalidSource.delayAfterFailure(1));
        assertThrows(IllegalArgumentException.class, () -> invalidSource.delayAfterFailure(0));
    }
}
