package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Static IAM group scoped to one organization. */
public record IdentityGroup(
        DomainIdentifier id, DomainIdentifier organizationId, String code, String displayName,
        boolean systemGroup, Instant createdAt, Instant updatedAt, Instant deletedAt) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9._-]{1,95}");

    public IdentityGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        code = normalizeCode(code);
        displayName = IdentityUser.text(displayName, "displayName", 200);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean deleted() { return deletedAt != null; }

    public IdentityGroup rename(String name, Instant now) {
        if (deleted()) throw new IdentityAccessException("IAM_GROUP_DELETED", "deleted group cannot be updated");
        return new IdentityGroup(id, organizationId, code, name, systemGroup, createdAt, Objects.requireNonNull(now, "now"), null);
    }

    public IdentityGroup delete(Instant now) {
        if (systemGroup) throw new IdentityAccessException("IAM_SYSTEM_GROUP_PROTECTED", "system group cannot be deleted");
        if (deleted()) return this;
        return new IdentityGroup(id, organizationId, code, displayName, false, createdAt, now, now);
    }

    public static String normalizeCode(String value) {
        Objects.requireNonNull(value, "code");
        IdentityUser.rejectIsoControls(value, "code");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid group code");
        return normalized;
    }
}
