package io.infranexum.identity.local.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Opaque server-side browser session; only hashes of bearer and CSRF tokens are persisted. */
public record LocalSession(
        DomainIdentifier id,
        DomainIdentifier accountId,
        String tokenHash,
        String csrfHash,
        long securityEpoch,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt) {
    public LocalSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        tokenHash = requireHash(tokenHash, "tokenHash");
        csrfHash = requireHash(csrfHash, "csrfHash");
        if (securityEpoch < 0) throw new IllegalArgumentException("securityEpoch must be non-negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (!absoluteExpiresAt.isAfter(createdAt)) throw new IllegalArgumentException("absolute expiry must be after creation");
    }

    public boolean usableAt(Instant now, long currentSecurityEpoch) {
        return revokedAt == null
                && securityEpoch == currentSecurityEpoch
                && now.isBefore(idleExpiresAt)
                && now.isBefore(absoluteExpiresAt);
    }

    private static String requireHash(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be SHA-256 hex");
        return value;
    }
}
