package io.infranexum.core.compatibility;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable domain failure exposed by the schema registry application boundary. */
public final class SchemaRegistryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,95}");
    private final String code;

    public SchemaRegistryException(String code, String message) {
        super(requireMessage(message));
        String normalized = Objects.requireNonNull(code, "code").strip();
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid code");
        this.code = normalized;
    }

    public String code() {
        return code;
    }

    private static String requireMessage(String value) {
        Objects.requireNonNull(value, "message");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 1024 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid message");
        }
        return normalized;
    }
}
