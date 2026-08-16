package io.infranexum.integrations;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable operator-defined connector instance key used in routes, metrics and persistence. */
public record ConnectorKey(String value) {
    private static final Pattern VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{2,79}");

    public ConnectorKey {
        Objects.requireNonNull(value, "value");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("connector key must match " + VALUE.pattern());
        }
    }

    @Override public String toString() { return value; }
}
