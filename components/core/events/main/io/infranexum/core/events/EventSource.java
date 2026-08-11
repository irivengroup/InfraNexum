package io.infranexum.core.events;

import java.util.Objects;

/** Stable producer identity included in every public event envelope. */
public record EventSource(String value) {
    public EventSource {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("event source must not be blank");
        }
        if (value.length() > 255) {
            throw new IllegalArgumentException("event source exceeds 255 characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("event source must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
