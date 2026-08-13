package io.infranexum.identity.local.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Persisted local human identity and credential security state. */
public record LocalAccount(
        DomainIdentifier id,
        String username,
        String displayName,
        String passwordHash,
        boolean mustChange,
        LocalAccountStatus status,
        int failedAttempts,
        Instant lockedUntil,
        long securityEpoch,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public LocalAccount {
        Objects.requireNonNull(id, "id");
        username = canonicalUsername(username);
        displayName = requireText(displayName, "displayName", 160);
        passwordHash = requireText(passwordHash, "passwordHash", 1024);
        Objects.requireNonNull(status, "status");
        if (failedAttempts < 0) throw new IllegalArgumentException("failedAttempts must be non-negative");
        if (securityEpoch < 0 || version < 0) throw new IllegalArgumentException("securityEpoch/version must be non-negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static String canonicalUsername(String value) {
        String result = requireText(value, "username", 128).toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9][a-z0-9._@-]{2,127}")) {
            throw new IllegalArgumentException("username format is invalid");
        }
        return result;
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    private static String requireText(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return result;
    }
}
