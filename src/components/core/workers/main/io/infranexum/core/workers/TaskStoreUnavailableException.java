package io.infranexum.core.workers;

import java.util.Objects;

/**
 * Signals a temporary loss of the durable task store.
 *
 * <p>This exception is reserved for infrastructure conditions where retrying a later worker
 * iteration is safe, such as a database writer failover. Programming errors, schema failures and
 * invalid task state must use their normal exception types so the worker loop still fails closed.
 */
public final class TaskStoreUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TaskStoreUnavailableException(Throwable cause) {
        super("task store temporarily unavailable", Objects.requireNonNull(cause, "cause"));
    }
}
