package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class UuidV7GeneratorTest {
    private static final long MAX_TIMESTAMP = 0x0000_ffff_ffff_ffffL;
    private static final long MAX_RAND_B = 0x3fff_ffff_ffff_ffffL;

    @Test
    void defaultGeneratorProducesRfc9562Identifier() {
        DomainIdentifier identifier = new UuidV7Generator().next();
        assertEquals(7, identifier.value().version());
        assertEquals(2, identifier.value().variant());
    }

    @Test
    void generatesMonotonicIdentifiersForSameOrRegressedClock() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        UuidV7Generator generator = new UuidV7Generator(clock, new FixedRandom(7, 11));
        DomainIdentifier first = generator.next();
        DomainIdentifier second = generator.next();
        clock.set(1_699_999_999_999L);
        DomainIdentifier third = generator.next();
        assertEquals(1_700_000_000_000L, first.unixEpochMillis());
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(third) < 0);
    }

    @Test
    void advancesRandAThenTimestampWhenPayloadWraps() throws Exception {
        UuidV7Generator generator = new UuidV7Generator(
                Clock.fixed(Instant.ofEpochMilli(10L), ZoneOffset.UTC), new FixedRandom(0, 0));
        generator.next();
        set(generator, "randB", MAX_RAND_B);
        set(generator, "randA", 1);
        DomainIdentifier randAAdvanced = generator.next();
        assertEquals(10L, randAAdvanced.unixEpochMillis());
        assertEquals(2, extractRandA(randAAdvanced));

        set(generator, "randB", MAX_RAND_B);
        set(generator, "randA", 0x0fff);
        DomainIdentifier timestampAdvanced = generator.next();
        assertEquals(11L, timestampAdvanced.unixEpochMillis());
        assertEquals(0, extractRandA(timestampAdvanced));
    }

    @Test
    void rejectsInvalidClockRangeAndExhaustedSequence() throws Exception {
        UuidV7Generator negative = new UuidV7Generator(
                Clock.fixed(Instant.ofEpochMilli(-1L), ZoneOffset.UTC), new FixedRandom(0, 0));
        assertThrows(IllegalStateException.class, negative::next);

        UuidV7Generator overflow = new UuidV7Generator(new MutableClock(MAX_TIMESTAMP + 1), new FixedRandom(0, 0));
        assertThrows(IllegalStateException.class, overflow::next);

        UuidV7Generator exhausted = new UuidV7Generator(new MutableClock(MAX_TIMESTAMP), new FixedRandom(0, 0));
        exhausted.next();
        set(exhausted, "randB", MAX_RAND_B);
        set(exhausted, "randA", 0x0fff);
        assertThrows(IllegalStateException.class, exhausted::next);
    }

    @Test
    void rejectsNullDependencies() {
        assertThrows(NullPointerException.class, () -> new UuidV7Generator(null, new FixedRandom(0, 0)));
        assertThrows(NullPointerException.class, () -> new UuidV7Generator(Clock.systemUTC(), null));
    }

    private static int extractRandA(DomainIdentifier identifier) {
        return (int) (identifier.value().getMostSignificantBits() & 0x0fffL);
    }

    private static void set(UuidV7Generator generator, String name, Object value) throws Exception {
        Field field = UuidV7Generator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(generator, value);
    }

    private static final class FixedRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final int integer;
        private final long longValue;

        private FixedRandom(int integer, long longValue) {
            this.integer = integer;
            this.longValue = longValue;
        }

        @Override public int nextInt(int bound) { return Math.floorMod(integer, bound); }
        @Override public long nextLong() { return longValue; }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(long initial) { millis = new AtomicLong(initial); }
        private void set(long value) { millis.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis.get(); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    }
}
