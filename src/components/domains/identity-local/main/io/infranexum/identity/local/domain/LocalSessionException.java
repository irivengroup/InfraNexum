package io.infranexum.identity.local.domain;

import java.io.Serial;

public final class LocalSessionException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    public LocalSessionException(String message) { super(message); }
}
