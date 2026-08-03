package io.infranexum.core.capabilities;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated stable capability identifier. */
public record CapabilityCode(String value) implements Comparable<CapabilityCode> {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");

    public CapabilityCode {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid capability code: " + value);
        }
    }

    @Override
    public int compareTo(CapabilityCode other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
