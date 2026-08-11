package io.infranexum.core.contracts;

import java.util.Objects;
import java.util.UUID;

/** Stable UUIDv7 identifier used at every InfraNexum domain boundary. */
public record DomainIdentifier(UUID value) implements Comparable<DomainIdentifier> {
    public DomainIdentifier {
        Objects.requireNonNull(value, "value");
        if (value.version() != 7) {
            throw new IllegalArgumentException("domain identifier must be UUIDv7");
        }
        if (value.variant() != 2) {
            throw new IllegalArgumentException("domain identifier must use the RFC 9562 variant");
        }
    }

    /** Parses and validates a canonical UUIDv7 string. */
    public static DomainIdentifier parse(String value) {
        Objects.requireNonNull(value, "value");
        return new DomainIdentifier(UUID.fromString(value));
    }

    /** Returns the embedded Unix epoch timestamp in milliseconds. */
    public long unixEpochMillis() {
        return value.getMostSignificantBits() >>> 16;
    }

    @Override
    public int compareTo(DomainIdentifier other) {
        Objects.requireNonNull(other, "other");
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
