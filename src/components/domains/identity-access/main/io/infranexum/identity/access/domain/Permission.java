package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Atomic RBAC permission with a stable machine-readable code. */
public record Permission(
        DomainIdentifier id, DomainIdentifier organizationId, String code, String resourceType, String action,
        String sensitivity, ScopeKind scopeKind, boolean systemDefined, boolean active,
        Instant createdAt, Instant updatedAt, Instant deletedAt) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+");
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    public Permission {
        Objects.requireNonNull(id, "id");
        code = normalizeCode(code);
        resourceType = token(resourceType, "resourceType");
        action = token(action, "action");
        sensitivity = token(sensitivity, "sensitivity").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(scopeKind, "scopeKind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (systemDefined && organizationId != null) throw new IllegalArgumentException("system permission must be platform-owned");
    }

    public boolean deleted() { return deletedAt != null; }

    public Permission update(String requestedResourceType, String requestedAction, String requestedSensitivity, ScopeKind requestedScope, boolean requestedActive, Instant now) {
        if (deleted()) throw new IdentityAccessException("IAM_PERMISSION_DELETED", "deleted permission cannot be updated");
        if (systemDefined) throw new IdentityAccessException("IAM_SYSTEM_PERMISSION_PROTECTED", "system permission cannot be modified");
        return new Permission(id, organizationId, code, requestedResourceType, requestedAction, requestedSensitivity, requestedScope, false, requestedActive, createdAt, Objects.requireNonNull(now, "now"), null);
    }

    public Permission delete(Instant now) {
        if (systemDefined) throw new IdentityAccessException("IAM_SYSTEM_PERMISSION_PROTECTED", "system permission cannot be deleted");
        if (deleted()) return this;
        return new Permission(id, organizationId, code, resourceType, action, sensitivity, scopeKind, false, false, createdAt, now, now);
    }

    public static String normalizeCode(String value) {
        Objects.requireNonNull(value, "code");
        IdentityUser.rejectIsoControls(value, "code");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160 || !CODE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid permission code");
        return normalized;
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        IdentityUser.rejectIsoControls(value, field);
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(normalized).matches()) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }
}
