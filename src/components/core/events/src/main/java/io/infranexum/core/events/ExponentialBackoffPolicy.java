package io.infranexum.core.events;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Bounded exponential backoff with injectable jitter for deterministic tests. */
public final class ExponentialBackoffPolicy implements RetryPolicy {
    private final int maximumAttempts;
    private final Duration initialDelay;
    private final Duration maximumDelay;
    private final double jitterRatio;
    private final DoubleSupplier jitterSource;

    public ExponentialBackoffPolicy(
            int maximumAttempts,
            Duration initialDelay,
            Duration maximumDelay,
            double jitterRatio,
            DoubleSupplier jitterSource) {
        if (maximumAttempts < 1 || maximumAttempts > 1_000) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 1000");
        }
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must be >= initialDelay");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
        this.maximumAttempts = maximumAttempts;
        this.jitterRatio = jitterRatio;
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    @Override
    public int maximumAttempts() {
        return maximumAttempts;
    }

    @Override
    public Duration delayAfterFailure(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        long initialMillis = initialDelay.toMillis();
        int shift = Math.min(attempts - 1, 62);
        long multiplier = 1L << shift;
        long exponential;
        try {
            exponential = Math.multiplyExact(initialMillis, multiplier);
        } catch (ArithmeticException ignored) {
            exponential = Long.MAX_VALUE;
        }
        long bounded = Math.min(exponential, maximumDelay.toMillis());
        double sample = jitterSource.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0.0 || sample > 1.0) {
            throw new IllegalStateException("jitter source must produce a value between 0 and 1");
        }
        long jitter = Math.round(bounded * jitterRatio * sample);
        long total;
        try {
            total = Math.addExact(bounded, jitter);
        } catch (ArithmeticException ignored) {
            total = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(total, maximumDelay.toMillis()));
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException(field + " must be at least one millisecond");
        }
        return value;
    }
}
