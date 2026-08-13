package io.infranexum.identity.access.domain;

import java.util.Objects;

/** Stable domain failure exposed by the IAM RBAC bounded context. */
public final class IdentityAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public IdentityAccessException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = token(code);
    }

    public String code() { return code; }

    private static String token(String value) {
        Objects.requireNonNull(value, "code");
        String normalized = value.strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("invalid IAM error code");
        return normalized;
    }
}
