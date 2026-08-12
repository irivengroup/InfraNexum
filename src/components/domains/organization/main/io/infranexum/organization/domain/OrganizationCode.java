package io.infranexum.organization.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable normalized business code for an organization. */
public record OrganizationCode(String value) implements Comparable<OrganizationCode> {
    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,31}");
    public OrganizationCode {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid organization code");
    }
    @Override public int compareTo(OrganizationCode other) { return value.compareTo(Objects.requireNonNull(other, "other").value); }
    @Override public String toString() { return value; }
}
