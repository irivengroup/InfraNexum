package io.infranexum.core.contracts;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread-safe RFC 9562 UUIDv7 generator.
 *
 * <p>When several identifiers are generated in the same millisecond, or when
 * the wall clock moves backwards, the 74 random payload bits are incremented.
 * This preserves local monotonic ordering without trusting a regressed clock.
 */
public final class UuidV7Generator {
    private static final long RAND_B_MASK = 0x3fff_ffff_ffff_ffffL;
    private final Clock clock;
    private final SecureRandom random;
    private long lastTimestamp = -1L;
    private int randA;
    private long randB;

    public UuidV7Generator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    public UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Generates the next locally monotonic UUIDv7 identifier. */
    public synchronized DomainIdentifier next() {
        long now = clock.millis();
        if (now < 0 || now > 0x0000_ffff_ffff_ffffL) {
            throw new IllegalStateException("clock is outside the UUIDv7 timestamp range");
        }
        if (now > lastTimestamp) {
            lastTimestamp = now;
            randA = random.nextInt(1 << 12);
            randB = random.nextLong() & RAND_B_MASK;
        } else {
            incrementPayload();
        }

        long mostSignificant = (lastTimestamp << 16) | 0x7000L | randA;
        long leastSignificant = 0x8000_0000_0000_0000L | randB;
        return new DomainIdentifier(new UUID(mostSignificant, leastSignificant));
    }

    private void incrementPayload() {
        if (randB < RAND_B_MASK) {
            randB++;
            return;
        }
        randB = 0L;
        if (randA < 0x0fff) {
            randA++;
            return;
        }
        if (lastTimestamp == 0x0000_ffff_ffff_ffffL) {
            throw new IllegalStateException("UUIDv7 sequence exhausted");
        }
        lastTimestamp++;
        randA = 0;
    }
}
