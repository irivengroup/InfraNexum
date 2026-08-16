package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable registry snapshot for one schema key and semantic version. */
public final class RegisteredSchema {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9.-]{2,159}");
    private static final Pattern OWNER = Pattern.compile("[a-z][a-z0-9._-]{2,159}");

    private final DomainIdentifier id;
    private final String schemaKey;
    private final SchemaKind kind;
    private final String owner;
    private final ContractVersion version;
    private final RegistryStatus status;
    private final String definitionJson;
    private final String checksumSha256;
    private final long revision;
    private final Instant effectiveAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant publishedAt;
    private final Instant deprecatedAt;
    private final Instant sunsetAt;
    private final String deprecationReason;
    private final String compatibilityEvidence;
    private final String breakingApprovalReference;

    public RegisteredSchema(
            DomainIdentifier id,
            String schemaKey,
            SchemaKind kind,
            String owner,
            ContractVersion version,
            RegistryStatus status,
            String definitionJson,
            String checksumSha256,
            long revision,
            Instant effectiveAt,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant deprecatedAt,
            Instant sunsetAt,
            String deprecationReason,
            String compatibilityEvidence,
            String breakingApprovalReference) {
        this.id = Objects.requireNonNull(id, "id");
        this.schemaKey = schemaKey(schemaKey);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = owner(owner);
        this.version = Objects.requireNonNull(version, "version");
        this.status = Objects.requireNonNull(status, "status");
        this.definitionJson = json(definitionJson);
        this.checksumSha256 = checksum(checksumSha256);
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        this.revision = revision;
        this.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.publishedAt = publishedAt;
        this.deprecatedAt = deprecatedAt;
        this.sunsetAt = sunsetAt;
        this.deprecationReason = nullableText(deprecationReason, 500);
        this.compatibilityEvidence = nullableText(compatibilityEvidence, 4000);
        this.breakingApprovalReference = nullableText(breakingApprovalReference, 240);
        validateLifecycle();
    }

    public DomainIdentifier id() { return id; }
    public String schemaKey() { return schemaKey; }
    public SchemaKind kind() { return kind; }
    public String owner() { return owner; }
    public ContractVersion version() { return version; }
    public RegistryStatus status() { return status; }
    public String definitionJson() { return definitionJson; }
    public String checksumSha256() { return checksumSha256; }
    public long revision() { return revision; }
    public Instant effectiveAt() { return effectiveAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant publishedAt() { return publishedAt; }
    public Instant deprecatedAt() { return deprecatedAt; }
    public Instant sunsetAt() { return sunsetAt; }
    public String deprecationReason() { return deprecationReason; }
    public String compatibilityEvidence() { return compatibilityEvidence; }
    public String breakingApprovalReference() { return breakingApprovalReference; }

    public RegisteredSchema updateDraft(String newDefinitionJson, String newChecksum, Instant now) {
        requireDraft();
        return new RegisteredSchema(id, schemaKey, kind, owner, version, status, newDefinitionJson, newChecksum,
                revision + 1, effectiveAt, createdAt, Objects.requireNonNull(now, "now"), null, null, null, null, null, null);
    }

    public RegisteredSchema publish(
            Instant now, CompatibilityReport compatibility, String evidence, String breakingApprovalReference) {
        requireDraft();
        Objects.requireNonNull(compatibility, "compatibility");
        if (compatibility.verdict() == CompatibilityVerdict.BREAKING
                && (breakingApprovalReference == null || breakingApprovalReference.isBlank())) {
            throw new SchemaRegistryException(
                    "SCHEMA_BREAKING_APPROVAL_REQUIRED",
                    "breaking schema publication requires an architecture approval reference");
        }
        if (compatibility.verdict() == CompatibilityVerdict.INDETERMINATE) {
            throw new SchemaRegistryException(
                    "SCHEMA_COMPATIBILITY_INDETERMINATE",
                    "schema compatibility could not be proven automatically");
        }
        Instant published = Objects.requireNonNull(now, "now");
        return new RegisteredSchema(id, schemaKey, kind, owner, version, RegistryStatus.PUBLISHED, definitionJson,
                checksumSha256, revision + 1, effectiveAt, createdAt, published, published, null, null, null,
                evidence, compatibility.verdict() == CompatibilityVerdict.BREAKING ? breakingApprovalReference : null);
    }

    public RegisteredSchema deprecate(Instant now, Instant sunset, String reason) {
        if (status != RegistryStatus.PUBLISHED) {
            throw new SchemaRegistryException("SCHEMA_NOT_PUBLISHED", "only a published schema can be deprecated");
        }
        Objects.requireNonNull(sunset, "sunset");
        String normalizedReason = nullableText(reason, 500);
        if (normalizedReason == null) throw new IllegalArgumentException("deprecation reason is required");
        Instant deprecated = Objects.requireNonNull(now, "now");
        if (!sunset.isAfter(deprecated)) throw new IllegalArgumentException("sunset must be after deprecation time");
        return new RegisteredSchema(id, schemaKey, kind, owner, version, RegistryStatus.DEPRECATED, definitionJson,
                checksumSha256, revision + 1, effectiveAt, createdAt, deprecated, publishedAt, deprecated, sunset,
                normalizedReason, compatibilityEvidence, breakingApprovalReference);
    }

    private void requireDraft() {
        if (status != RegistryStatus.DRAFT) {
            throw new SchemaRegistryException("SCHEMA_IMMUTABLE", "published or deprecated schemas are immutable");
        }
    }

    private void validateLifecycle() {
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (status == RegistryStatus.DRAFT && (publishedAt != null || deprecatedAt != null || sunsetAt != null)) {
            throw new IllegalArgumentException("draft schema cannot have publication lifecycle timestamps");
        }
        if (status == RegistryStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("published schema requires publishedAt");
        }
        if (status == RegistryStatus.DEPRECATED
                && (publishedAt == null || deprecatedAt == null || sunsetAt == null || deprecationReason == null)) {
            throw new IllegalArgumentException("deprecated schema requires publication and deprecation metadata");
        }
    }

    private static String schemaKey(String value) {
        String normalized = token(value, "schemaKey", 160).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) throw new IllegalArgumentException("invalid schemaKey");
        return normalized;
    }

    private static String owner(String value) {
        String normalized = token(value, "owner", 160).toLowerCase(Locale.ROOT);
        if (!OWNER.matcher(normalized).matches()) throw new IllegalArgumentException("invalid owner");
        return normalized;
    }

    private static String json(String value) {
        Objects.requireNonNull(value, "definitionJson");
        String normalized = value.strip();
        if (normalized.length() < 2 || normalized.length() > 1_048_576 || !normalized.startsWith("{") || !normalized.endsWith("}")) {
            throw new IllegalArgumentException("definitionJson must be a bounded JSON object");
        }
        return normalized;
    }

    private static String checksum(String value) {
        String normalized = token(value, "checksumSha256", 64).toLowerCase(Locale.ROOT);
        if (normalized.length() != 64 || !normalized.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
            throw new IllegalArgumentException("invalid SHA-256 checksum");
        }
        return normalized;
    }

    private static String token(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    private static String nullableText(String value, int maximum) {
        if (value == null) return null;
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid text value");
        }
        if (value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("invalid text value");
        }
        return normalized;
    }
}
