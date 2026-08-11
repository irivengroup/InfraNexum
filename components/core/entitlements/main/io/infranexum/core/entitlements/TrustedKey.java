package io.infranexum.core.entitlements;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/** Trusted Ed25519 verification key with an explicit validity interval. */
public record TrustedKey(String keyId, PublicKey publicKey, Instant validFrom, Instant validUntil) {
    public TrustedKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        if (keyId.isBlank() || !"EdDSA".equals(publicKey.getAlgorithm()) || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("invalid trusted Ed25519 key");
        }
    }

    public boolean isValidAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(validFrom) && instant.isBefore(validUntil);
    }
}
