package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** RBAC role aggregating one or more atomic permissions. */
public record Role(
        DomainIdentifier id, DomainIdentifier organizationId, String code, String displayName,
        ScopeKind scopeKind, boolean systemRole, boolean active, Instant createdAt, Instant updatedAt, Instant deletedAt) {
    public static final String PLATFORM_ADMIN_CODE = "system.platform_admin";
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+");

    public Role {
        Objects.requireNonNull(id, "id");
        code = normalizeCode(code);
        displayName = IdentityUser.text(displayName, "displayName", 200);
        Objects.requireNonNull(scopeKind, "scopeKind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (systemRole && organizationId != null) throw new IllegalArgumentException("system role must be platform-owned");
    }

    public boolean deleted() { return deletedAt != null; }

    public Role update(String requestedCode, String requestedName, Instant now) {
        if (deleted()) throw new IdentityAccessException("IAM_ROLE_DELETED", "deleted role cannot be updated");
        String normalizedCode = normalizeCode(requestedCode);
        if (systemRole && !code.equals(normalizedCode)) throw new IdentityAccessException("IAM_SYSTEM_ROLE_PROTECTED", "system role cannot be renamed");
        return new Role(id, organizationId, normalizedCode, requestedName, scopeKind, systemRole, active, createdAt, now, null);
    }

    public Role delete(Instant now) {
        if (systemRole) throw new IdentityAccessException("IAM_SYSTEM_ROLE_PROTECTED", "system role cannot be deleted");
        if (deleted()) return this;
        return new Role(id, organizationId, code, displayName, scopeKind, false, false, createdAt, now, now);
    }

    public static String normalizeCode(String value) {
        Objects.requireNonNull(value, "code");
        IdentityUser.rejectIsoControls(value, "code");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160 || !CODE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid role code");
        return normalized;
    }
}
