package io.infranexum.integrations;

/** Requested connector endpoint is not configured and enabled in this installation. */
public final class ConnectorEndpointUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorEndpointUnavailableException(String message) { super(message); }
}
