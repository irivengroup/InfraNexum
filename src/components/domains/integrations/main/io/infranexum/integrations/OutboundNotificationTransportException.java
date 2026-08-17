package io.infranexum.integrations;

/** Safe transport failure classification used to choose retry versus immediate DLQ. */
public final class OutboundNotificationTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;
    private final String code;

    public OutboundNotificationTransportException(String code, boolean retryable) {
        super(code);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("invalid notification transport code");
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
