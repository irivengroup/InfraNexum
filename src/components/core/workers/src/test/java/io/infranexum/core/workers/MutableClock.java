package io.infranexum.core.workers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic mutable UTC clock for lease, retry and cancellation tests. */
final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;

    MutableClock(Instant initial) {
        instant = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        instant.updateAndGet(value -> value.plus(duration));
    }

    void set(Instant value) {
        instant.set(Objects.requireNonNull(value, "value"));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("MutableClock supports UTC only");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
