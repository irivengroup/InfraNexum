package io.infranexum.integrations;

/** Raised when an outbound notification delivery identifier does not exist. */
public final class OutboundNotificationNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public OutboundNotificationNotFoundException(String message) { super(message); }
}
