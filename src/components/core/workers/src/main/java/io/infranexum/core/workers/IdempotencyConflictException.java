package io.infranexum.core.workers;

/** Indicates reuse of an idempotency key with different task semantics. */
public final class IdempotencyConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
