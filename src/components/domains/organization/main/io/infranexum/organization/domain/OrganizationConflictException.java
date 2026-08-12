package io.infranexum.organization.domain;

import java.util.Objects;

/** Stable conflict used for uniqueness, optimistic locking and idempotency collisions. */
public final class OrganizationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public OrganizationConflictException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }

    public String code() {
        return code;
    }
}
