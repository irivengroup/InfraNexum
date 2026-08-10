package io.infranexum.core.workers;

/** Cooperative signal emitted by a handler after observing a cancellation request. */
public final class TaskCancelledException extends Exception {
    private static final long serialVersionUID = 1L;

    public TaskCancelledException(String message) {
        super(message);
    }
}
