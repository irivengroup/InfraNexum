package io.infranexum.integrations;

/** Requested synchronization run or connector checkpoint state does not exist. */
public final class ConnectorSyncNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorSyncNotFoundException(String message) { super(message); }
}
