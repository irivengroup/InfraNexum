package io.infranexum.integrations;

/** Requested durable connector delivery does not exist. */
public final class ConnectorDeliveryNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConnectorDeliveryNotFoundException(String message) { super(message); }
}
