package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** HMAC-protected temporal anchor persisted in both the database and an independent store. */
public record IntegrityProof(
        DomainIdentifier installationId,
        String fingerprint,
        Instant evaluationStartedAt,
        Instant lastReliableAt,
        long generation,
        String mac) {
    public IntegrityProof {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(evaluationStartedAt, "evaluationStartedAt");
        Objects.requireNonNull(lastReliableAt, "lastReliableAt");
        InstallationIdentity.requireWholeSecond(evaluationStartedAt, "evaluationStartedAt");
        InstallationIdentity.requireWholeSecond(lastReliableAt, "lastReliableAt");
        if (lastReliableAt.isBefore(evaluationStartedAt) || generation < 1) {
            throw new IllegalArgumentException("invalid trusted time proof interval or generation");
        }
        Objects.requireNonNull(mac, "mac");
        try {
            if (Base64.getDecoder().decode(mac).length != 32) {
                throw new IllegalArgumentException("integrity MAC must contain 32 bytes");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("integrity MAC must be Base64 HMAC-SHA256 data", error);
        }
    }

    Map<String, Object> unsignedValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evaluation_started_at", evaluationStartedAt.toString());
        value.put("fingerprint", fingerprint);
        value.put("generation", generation);
        value.put("installation_id", installationId.toString());
        value.put("last_reliable_at", lastReliableAt.toString());
        return Map.copyOf(value);
    }
}
