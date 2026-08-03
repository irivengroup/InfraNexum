package io.infranexum.core.entitlements;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable trust-store implementation used by composition roots and deterministic tests. */
public final class InMemoryTrustedKeyStore implements TrustedKeyStore {
    private final Map<String, TrustedKey> keys;

    public InMemoryTrustedKeyStore(Map<String, TrustedKey> keys) {
        this.keys = Map.copyOf(Objects.requireNonNull(keys, "keys"));
        if (this.keys.isEmpty() || this.keys.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().keyId()))) {
            throw new IllegalArgumentException("trusted key map must be non-empty and keyed by keyId");
        }
    }

    @Override
    public Optional<TrustedKey> find(String keyId) {
        return Optional.ofNullable(keys.get(Objects.requireNonNull(keyId, "keyId")));
    }
}
