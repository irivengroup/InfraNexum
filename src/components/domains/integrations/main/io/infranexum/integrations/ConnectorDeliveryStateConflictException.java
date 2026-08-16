package io.infranexum.integrations;

/** Signals a valid connector delivery request that conflicts with the delivery's current durable state. */
public final class ConnectorDeliveryStateConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConnectorDeliveryStateConflictException(String message) {
        super(message);
    }
}
