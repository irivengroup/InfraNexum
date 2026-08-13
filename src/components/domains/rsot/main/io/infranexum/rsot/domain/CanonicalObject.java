package io.infranexum.rsot.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minimal canonical object identity for PGM-06-E01.
 *
 * <p>Specialist payloads stay in their owning bounded contexts. This aggregate only establishes
 * the stable canonical identity, organization weak reference, version, schema version and lifecycle
 * required before source ingestion/reconciliation is implemented.</p>
 */
public final class CanonicalObject {
    private static final Pattern OBJECT_TYPE = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9_-]*)+");

    private final DomainIdentifier id;
    private final String objectType;
    private final long version;
    private final DomainIdentifier organizationId;
    private final String schemaVersion;
    private final CanonicalLifecycle lifecycle;
    private final Instant createdAt;
    private final Instant updatedAt;

    public CanonicalObject(
            DomainIdentifier id,
            String objectType,
            long version,
            DomainIdentifier organizationId,
            String schemaVersion,
            CanonicalLifecycle lifecycle,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.objectType = objectType(objectType);
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        this.version = version;
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.schemaVersion = token(schemaVersion, "schemaVersion", 64);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (lifecycle.effectiveFrom().isBefore(createdAt)) {
            throw new IllegalArgumentException("lifecycle effectiveFrom precedes creation");
        }
    }

    public DomainIdentifier id() { return id; }
    public String objectType() { return objectType; }
    public long version() { return version; }
    public DomainIdentifier organizationId() { return organizationId; }
    public String schemaVersion() { return schemaVersion; }
    public CanonicalLifecycle lifecycle() { return lifecycle; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    private static String objectType(String value) {
        String normalized = token(value, "objectType", 160).toLowerCase(Locale.ROOT);
        if (!OBJECT_TYPE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid objectType");
        return normalized;
    }

    private static String token(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
