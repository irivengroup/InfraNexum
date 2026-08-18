package io.infranexum.integrations;

/** Execution request was denied by connector governance; no mutation was attempted. */
public final class ConnectorSyncPolicyDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorSyncPolicyDeniedException(String message) { super(message); }
}
