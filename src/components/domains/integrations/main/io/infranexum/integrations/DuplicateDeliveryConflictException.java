package io.infranexum.integrations;

/** Same provider delivery key was reused with different content. */
public final class DuplicateDeliveryConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public DuplicateDeliveryConflictException(String message) { super(message); }
}
