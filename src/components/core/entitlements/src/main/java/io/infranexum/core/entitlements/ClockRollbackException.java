package io.infranexum.core.entitlements;

import java.io.Serial;

/** Fail-closed signal raised when trusted temporal evidence regresses or diverges. */
public final class ClockRollbackException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ClockRollbackException(String message) {
        super(message);
    }
}
