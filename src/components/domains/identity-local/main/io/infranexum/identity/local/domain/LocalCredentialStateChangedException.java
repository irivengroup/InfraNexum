package io.infranexum.identity.local.domain;

import java.io.Serial;

/** Internal optimistic-security-state conflict; never exposed with account-specific detail. */
public final class LocalCredentialStateChangedException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    public LocalCredentialStateChangedException() {
        super("local credential security state changed concurrently");
    }
}
