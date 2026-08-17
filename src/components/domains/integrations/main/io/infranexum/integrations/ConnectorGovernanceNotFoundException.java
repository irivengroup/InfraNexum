package io.infranexum.integrations;

/** Raised when a governance operation targets an unknown connector key. */
public final class ConnectorGovernanceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorGovernanceNotFoundException(String message) { super(message); }
}
