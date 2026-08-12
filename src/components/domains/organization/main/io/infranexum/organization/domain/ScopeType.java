package io.infranexum.organization.domain;

import java.util.Locale;
import java.util.Objects;

/** Orthogonal governance scope dimensions required by the organization contract. */
public enum ScopeType {
    LEGAL,
    GEOGRAPHIC,
    OPERATIONAL,
    ADMINISTRATIVE,
    DATA;

    public static ScopeType parse(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("scope type must not be blank");
        }
        return valueOf(normalized.toUpperCase(Locale.ROOT));
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
