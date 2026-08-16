package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authoritative IAM user projection independent from a concrete authentication provider. */
public record IdentityUser(
        DomainIdentifier id, String login, String email, String displayName,
        IdentityUserStatus status, Instant createdAt, Instant updatedAt, Instant deletedAt) {
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._@-]{1,127}");

    public IdentityUser {
        Objects.requireNonNull(id, "id");
        login = canonicalLogin(login);
        email = optionalEmail(email);
        displayName = text(displayName, "displayName", 200);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == IdentityUserStatus.DELETED && deletedAt == null) throw new IllegalArgumentException("deleted user requires deletedAt");
        if (status != IdentityUserStatus.DELETED && deletedAt != null) throw new IllegalArgumentException("non-deleted user cannot have deletedAt");
    }

    public static IdentityUser pending(DomainIdentifier id, String login, String email, String displayName, Instant now) {
        return new IdentityUser(id, login, email, displayName, IdentityUserStatus.PENDING, now, now, null);
    }

    public IdentityUser activate(Instant now) { return transition(IdentityUserStatus.ACTIVE, now, null); }
    public IdentityUser suspend(Instant now) { return transition(IdentityUserStatus.SUSPENDED, now, null); }
    public IdentityUser delete(Instant now) { return transition(IdentityUserStatus.DELETED, now, now); }

    public IdentityUser updateProfile(String requestedEmail, String requestedDisplayName, Instant now) {
        if (status == IdentityUserStatus.DELETED) throw new IdentityAccessException("IAM_USER_DELETED", "deleted user cannot be updated");
        return new IdentityUser(id, login, requestedEmail, requestedDisplayName, status, createdAt, Objects.requireNonNull(now, "now"), null);
    }

    private IdentityUser transition(IdentityUserStatus target, Instant now, Instant deleted) {
        Objects.requireNonNull(now, "now");
        if (status == IdentityUserStatus.DELETED) throw new IdentityAccessException("IAM_USER_DELETED", "deleted user cannot transition");
        return new IdentityUser(id, login, email, displayName, target, createdAt, now, deleted);
    }

    public static String canonicalLogin(String value) {
        Objects.requireNonNull(value, "login");
        rejectIsoControls(value, "login");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN.matcher(normalized).matches()) throw new IllegalArgumentException("invalid login");
        return normalized;
    }

    private static String optionalEmail(String value) {
        if (value == null || value.isBlank()) return null;
        rejectIsoControls(value, "email");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !normalized.contains("@") || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    static String text(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        rejectIsoControls(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    static void rejectIsoControls(String value, String field) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
