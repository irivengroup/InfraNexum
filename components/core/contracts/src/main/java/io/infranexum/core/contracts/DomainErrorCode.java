package io.infranexum.core.contracts;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable machine-readable error code exposed by domain contract packs. */
public record DomainErrorCode(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");

    public DomainErrorCode {
        Objects.requireNonNull(value, "value");
        value = value.toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid domain error code: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
