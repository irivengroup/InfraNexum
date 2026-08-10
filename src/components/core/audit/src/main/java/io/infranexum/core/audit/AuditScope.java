package io.infranexum.core.audit;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable scope attached to every audit entry. */
public record AuditScope(String type, String id) implements Comparable<AuditScope> {
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");

    public AuditScope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        type = type.strip().toUpperCase(Locale.ROOT);
        id = id.strip();
        if (!TYPE.matcher(type).matches()) throw new IllegalArgumentException("invalid audit scope type");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("invalid audit scope id");
    }

    /** Creates the platform-wide scope used before an organization context exists. */
    public static AuditScope platform() {
        return new AuditScope("PLATFORM", "platform");
    }

    /** Creates an organization scope without leaking organization implementation details. */
    public static AuditScope organization(String organizationId) {
        return new AuditScope("ORGANIZATION", organizationId);
    }

    @Override
    public int compareTo(AuditScope other) {
        Objects.requireNonNull(other, "other");
        int byType = type.compareTo(other.type);
        return byType != 0 ? byType : id.compareTo(other.id);
    }
}
