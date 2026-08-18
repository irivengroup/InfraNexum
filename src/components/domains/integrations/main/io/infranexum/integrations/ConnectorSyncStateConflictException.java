package io.infranexum.integrations;

/** Fail-closed concurrency/lifecycle conflict in the durable connector synchronization state machine. */
public final class ConnectorSyncStateConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorSyncStateConflictException(String message) { super(message); }
}
