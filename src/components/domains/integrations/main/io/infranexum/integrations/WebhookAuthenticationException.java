package io.infranexum.integrations;

/** Fail-closed webhook authentication failure without exposing secret material. */
public final class WebhookAuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public WebhookAuthenticationException(String message) { super(message); }
}
