package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable revocation registry keyed by key ID and activation ID. */
public final class InMemoryRevocationRegistry implements RevocationRegistry {
    private final Map<String, Instant> revokedKeys;
    private final Map<DomainIdentifier, Instant> revokedActivations;

    public InMemoryRevocationRegistry(
            Map<String, Instant> revokedKeys, Map<DomainIdentifier, Instant> revokedActivations) {
        this.revokedKeys = Map.copyOf(Objects.requireNonNull(revokedKeys, "revokedKeys"));
        this.revokedActivations = Map.copyOf(Objects.requireNonNull(revokedActivations, "revokedActivations"));
    }

    @Override
    public boolean isKeyRevoked(String keyId, Instant at) {
        return effective(revokedKeys.get(Objects.requireNonNull(keyId, "keyId")), at);
    }

    @Override
    public boolean isActivationRevoked(DomainIdentifier activationId, Instant at) {
        return effective(revokedActivations.get(Objects.requireNonNull(activationId, "activationId")), at);
    }

    private static boolean effective(Instant revokedAt, Instant at) {
        Objects.requireNonNull(at, "at");
        return revokedAt != null && !at.isBefore(revokedAt);
    }
}
