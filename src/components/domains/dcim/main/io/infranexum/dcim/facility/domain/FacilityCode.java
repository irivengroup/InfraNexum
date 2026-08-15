package io.infranexum.dcim.facility.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Upper-case location code unique inside the normative parent scope. */
public record FacilityCode(String value) {
    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,63}$");
    public FacilityCode {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid facility code");
    }
    @Override public String toString() { return value; }
}
