package io.infranexum.identity.local.application;

import java.time.Duration;
import java.util.Objects;

/** Configurable implementation policy where the CDC defines bounded lockout but no fixed duration. */
public record LocalAuthenticationPolicy(
        int lockThreshold,
        Duration lockDuration,
        Duration idleTimeout,
        Duration absoluteTimeout,
        Duration touchInterval) {
    public LocalAuthenticationPolicy {
        if (lockThreshold < 1 || lockThreshold > 20) throw new IllegalArgumentException("lockThreshold out of range");
        Objects.requireNonNull(lockDuration, "lockDuration");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(absoluteTimeout, "absoluteTimeout");
        Objects.requireNonNull(touchInterval, "touchInterval");
        if (lockDuration.isNegative() || lockDuration.isZero()) throw new IllegalArgumentException("lockDuration must be positive");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) throw new IllegalArgumentException("idleTimeout must be positive");
        if (absoluteTimeout.compareTo(idleTimeout) < 0) throw new IllegalArgumentException("absoluteTimeout must be >= idleTimeout");
        if (touchInterval.isNegative()) throw new IllegalArgumentException("touchInterval must be non-negative");
    }
}
