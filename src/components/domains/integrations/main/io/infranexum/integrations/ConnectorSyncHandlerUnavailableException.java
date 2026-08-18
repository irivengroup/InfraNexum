package io.infranexum.integrations;

/** Raised when policy exists but no approved mutation handler is registered for that connector. */
public final class ConnectorSyncHandlerUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorSyncHandlerUnavailableException(ConnectorKey key) { super("connector synchronization handler unavailable: " + key.value()); }
}
