package io.infranexum.itam.partner.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable normalized business code, unique in the governing organization. */
public record PartnerCode(String value) implements Comparable<PartnerCode> {
    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,31}");
    public PartnerCode {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid partner code");
    }
    @Override public int compareTo(PartnerCode other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }
    @Override public String toString() { return value; }
}
