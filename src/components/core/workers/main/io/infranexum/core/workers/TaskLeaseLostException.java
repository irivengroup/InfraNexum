package io.infranexum.core.workers;

/** Raised when a stale worker attempts to mutate a task after losing its lease. */
public final class TaskLeaseLostException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TaskLeaseLostException(String message) {
        super(message);
    }
}
