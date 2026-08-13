package io.infranexum.rsot.domain;

import java.util.Objects;

/** Domain/application failure carrying a stable RSOT error code. */
public final class RsotException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public RsotException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = requireToken(code, "code");
    }

    public String code() {
        return code;
    }

    private static String requireToken(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 96 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
