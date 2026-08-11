package io.infranexum.core.events;

/** Signals that transactional work failed and its staged state was rolled back. */
public final class TransactionExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransactionExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
