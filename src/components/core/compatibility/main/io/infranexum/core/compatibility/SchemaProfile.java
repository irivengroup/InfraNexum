package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned composition of published schemas, independently publishable from the member contracts. */
public final class SchemaProfile {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9.-]{2,159}");
    private static final Pattern OWNER = Pattern.compile("[a-z][a-z0-9._-]{2,159}");
    private final DomainIdentifier id;
    private final String code;
    private final String owner;
    private final ContractVersion version;
    private final RegistryStatus status;
    private final List<SchemaProfileMember> members;
    private final String checksumSha256;
    private final long revision;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant publishedAt;
    private final Instant deprecatedAt;
    private final Instant sunsetAt;
    private final String deprecationReason;

    public SchemaProfile(
            DomainIdentifier id,
            String code,
            String owner,
            ContractVersion version,
            RegistryStatus status,
            List<SchemaProfileMember> members,
            String checksumSha256,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant deprecatedAt,
            Instant sunsetAt,
            String deprecationReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = code(code);
        this.owner = owner(owner);
        this.version = Objects.requireNonNull(version, "version");
        this.status = Objects.requireNonNull(status, "status");
        this.members = validatedMembers(members);
        this.checksumSha256 = checksum(checksumSha256);
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        this.revision = revision;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.publishedAt = publishedAt;
        this.deprecatedAt = deprecatedAt;
        this.sunsetAt = sunsetAt;
        this.deprecationReason = nullableText(deprecationReason, 500);
        validateLifecycle();
    }

    public DomainIdentifier id() { return id; }
    public String code() { return code; }
    public String owner() { return owner; }
    public ContractVersion version() { return version; }
    public RegistryStatus status() { return status; }
    public List<SchemaProfileMember> members() { return members; }
    public String checksumSha256() { return checksumSha256; }
    public long revision() { return revision; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant publishedAt() { return publishedAt; }
    public Instant deprecatedAt() { return deprecatedAt; }
    public Instant sunsetAt() { return sunsetAt; }
    public String deprecationReason() { return deprecationReason; }

    public SchemaProfile publish(Instant now) {
        requireDraft();
        Instant published = Objects.requireNonNull(now, "now");
        return new SchemaProfile(id, code, owner, version, RegistryStatus.PUBLISHED, members, checksumSha256,
                revision + 1, createdAt, published, published, null, null, null);
    }

    public SchemaProfile deprecate(Instant now, Instant sunset, String reason) {
        if (status != RegistryStatus.PUBLISHED) {
            throw new SchemaRegistryException("SCHEMA_PROFILE_NOT_PUBLISHED", "only a published profile can be deprecated");
        }
        Instant deprecated = Objects.requireNonNull(now, "now");
        Objects.requireNonNull(sunset, "sunset");
        String normalized = nullableText(reason, 500);
        if (normalized == null) throw new IllegalArgumentException("deprecation reason is required");
        if (!sunset.isAfter(deprecated)) throw new IllegalArgumentException("sunset must be after deprecation time");
        return new SchemaProfile(id, code, owner, version, RegistryStatus.DEPRECATED, members, checksumSha256,
                revision + 1, createdAt, deprecated, publishedAt, deprecated, sunset, normalized);
    }

    private void requireDraft() {
        if (status != RegistryStatus.DRAFT) {
            throw new SchemaRegistryException("SCHEMA_PROFILE_IMMUTABLE", "published or deprecated profiles are immutable");
        }
    }

    private void validateLifecycle() {
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (status == RegistryStatus.DRAFT && (publishedAt != null || deprecatedAt != null || sunsetAt != null)) {
            throw new IllegalArgumentException("draft profile cannot have lifecycle timestamps");
        }
        if (status == RegistryStatus.PUBLISHED && publishedAt == null) throw new IllegalArgumentException("publishedAt is required");
        if (status == RegistryStatus.DEPRECATED
                && (publishedAt == null || deprecatedAt == null || sunsetAt == null || deprecationReason == null)) {
            throw new IllegalArgumentException("deprecated profile requires lifecycle metadata");
        }
    }

    private static List<SchemaProfileMember> validatedMembers(List<SchemaProfileMember> values) {
        List<SchemaProfileMember> copy = List.copyOf(Objects.requireNonNull(values, "members"));
        if (copy.isEmpty() || copy.size() > 128) throw new IllegalArgumentException("profile must contain between 1 and 128 members");
        Set<DomainIdentifier> ids = new HashSet<>();
        for (int index = 0; index < copy.size(); index++) {
            SchemaProfileMember member = copy.get(index);
            if (member.position() != index + 1) throw new IllegalArgumentException("profile positions must be contiguous and ordered");
            if (!ids.add(member.schemaId())) throw new IllegalArgumentException("duplicate schema member");
        }
        return copy;
    }

    private static String code(String value) {
        String normalized = token(value, "code", 160).toLowerCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("invalid profile code");
        return normalized;
    }


    private static String owner(String value) {
        String normalized = token(value, "owner", 160).toLowerCase(Locale.ROOT);
        if (!OWNER.matcher(normalized).matches()) throw new IllegalArgumentException("invalid owner");
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
