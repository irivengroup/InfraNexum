package io.infranexum.core.entitlements;

import java.util.Base64;
import java.util.Objects;

/** Signed activation payload with a detached Ed25519 signature encoded in Base64. */
public record ActivationManifest(ActivationManifestPayload payload, String signature) {
    public ActivationManifest {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(signature, "signature");
        try {
            byte[] decoded = Base64.getDecoder().decode(signature);
            if (decoded.length != 64) {
                throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("signature must be valid Base64 Ed25519 data", error);
        }
    }

    public byte[] signatureBytes() {
        return Base64.getDecoder().decode(signature);
    }

    /** Canonical persisted document used for deterministic re-verification at every Server startup. */
    public String canonicalDocument() {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>(payload.canonicalValue());
        value.put("signature", signature);
        return CanonicalJson.string(value);
    }
}
