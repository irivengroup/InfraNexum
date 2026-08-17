package io.infranexum.integrations;

/** Raised when an operator action conflicts with the durable notification state. */
public final class OutboundNotificationStateConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public OutboundNotificationStateConflictException(String message) { super(message); }
}
