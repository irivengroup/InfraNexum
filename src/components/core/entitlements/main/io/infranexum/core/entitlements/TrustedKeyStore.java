package io.infranexum.core.entitlements;

import java.util.Optional;

/** Read-only trust store; client installations never receive private signing keys. */
public interface TrustedKeyStore {
    Optional<TrustedKey> find(String keyId);
}
