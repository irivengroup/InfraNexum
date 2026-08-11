package io.infranexum.core.workers;

/** Indicates that a bounded task store has reached its configured capacity. */
public final class TaskCapacityExceededException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TaskCapacityExceededException(String message) {
        super(message);
    }
}
