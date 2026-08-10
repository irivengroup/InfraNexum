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
    void capsShiftMultiplicationAndAdditionOverflowWithoutExceedingMaximumDelay() {
        RetryPolicy multiplicationOverflow = new ExponentialBackoffPolicy(
                3,
                Duration.ofMillis(Long.MAX_VALUE / 2 + 1),
                Duration.ofMillis(Long.MAX_VALUE),
                0.0,
                () -> 0.0);
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), multiplicationOverflow.delayAfterFailure(2));
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), multiplicationOverflow.delayAfterFailure(100));

        RetryPolicy additionOverflow = new ExponentialBackoffPolicy(
                1,
                Duration.ofMillis(Long.MAX_VALUE),
                Duration.ofMillis(Long.MAX_VALUE),
                1.0,
                () -> 1.0);
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), additionOverflow.delayAfterFailure(1));
    }

    @Test
    void rejectsEveryUnsafeConfigurationBoundary() {
        assertThrows(IllegalArgumentException.class, () -> policy(0, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1001, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(NullPointerException.class, () -> policy(1, null, Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ZERO, Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(-1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofNanos(1), Duration.ofSeconds(2), 0.0, () -> 0.0));
        assertThrows(NullPointerException.class, () -> policy(1, Duration.ofSeconds(1), null, 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(1), Duration.ZERO, 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(2), Duration.ofSeconds(1), 0.0, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), Double.NaN, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), -0.1, () -> 0.0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 1.1, () -> 0.0));
        assertThrows(NullPointerException.class, () -> policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.0, null));
    }

    @Test
    void rejectsInvalidAttemptAndEveryInvalidJitterSampleClass() {
        RetryPolicy valid = policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> 0.5);
        assertThrows(IllegalArgumentException.class, () -> valid.delayAfterFailure(0));

        RetryPolicy notFinite = policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> Double.NaN);
        RetryPolicy negative = policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> -0.1);
        RetryPolicy aboveOne = policy(1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1, () -> 1.1);
        assertThrows(IllegalStateException.class, () -> notFinite.delayAfterFailure(1));
        assertThrows(IllegalStateException.class, () -> negative.delayAfterFailure(1));
        assertThrows(IllegalStateException.class, () -> aboveOne.delayAfterFailure(1));
    }

    private static ExponentialBackoffPolicy policy(
            int maximumAttempts,
            Duration initialDelay,
            Duration maximumDelay,
            double jitterRatio,
            java.util.function.DoubleSupplier jitterSource) {
        return new ExponentialBackoffPolicy(maximumAttempts, initialDelay, maximumDelay, jitterRatio, jitterSource);
    }
}
