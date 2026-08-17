package io.infranexum.integrations;

import java.util.Objects;
import java.util.regex.Pattern;

/** Field-level authority declaration used by synchronization plans. */
public record ConnectorFieldAuthority(String field, ConnectorDataAuthority authority) {
    private static final Pattern FIELD = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");

    public ConnectorFieldAuthority {
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("connector governance field must match " + FIELD.pattern());
        }
        Objects.requireNonNull(authority, "authority");
    }
}
