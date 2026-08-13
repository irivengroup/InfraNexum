package io.infranexum.identity.local.domain;

import java.io.Serial;

/** Generic authentication failure deliberately avoids account-enumeration details. */
public final class LocalAuthenticationException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    public LocalAuthenticationException() { super("authentication failed"); }
}
