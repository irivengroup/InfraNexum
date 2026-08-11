package io.infranexum.core.contracts;

/** Raised when runtime configuration violates a startup invariant. */
public final class ConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }
}
