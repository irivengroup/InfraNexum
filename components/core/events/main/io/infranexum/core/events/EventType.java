package io.infranexum.core.events;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable dotted event type used at transactional and transport boundaries. */
public record EventType(String value) implements Comparable<EventType> {
    private static final Pattern FORMAT = Pattern.compile(
            "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*){2,7}\\.v[1-9][0-9]*");

    public EventType {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid event type: " + value);
        }
    }

    @Override
    public int compareTo(EventType other) {
        Objects.requireNonNull(other, "other");
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
