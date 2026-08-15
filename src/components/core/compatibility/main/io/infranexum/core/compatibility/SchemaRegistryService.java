package io.infranexum.core.compatibility;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core Contracts/Compatibility application service for versioned JSON schemas and composable profiles.
 *
 * <p>All mutations share the transactional outbox unit of work. Published revisions are immutable;
 * compatibility is checked before publication and breaking changes require an explicit architecture approval reference.</p>
 */
public final class SchemaRegistryService {
    private static final ContractVersion EVENT_VERSION = ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE = new EventSource("infranexum.core.compatibility");

    private final SchemaRegistryRepository repository;
    private final SchemaDefinitionInspector inspector;
    private final TransactionalEventStore events;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final SchemaRegistryFeaturePolicy features;

    public SchemaRegistryService(
            SchemaRegistryRepository repository,
            SchemaDefinitionInspector inspector,
            TransactionalEventStore events,
            AuditJournal audit,
            UuidV7Generator ids,
            Clock clock,
            SchemaRegistryFeaturePolicy features) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.events = Objects.requireNonNull(events, "events");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.features = Objects.requireNonNull(features, "features");
    }

    public RegisteredSchema createSchema(CreateSchemaCommand command, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ContractVersion version = ContractVersion.parse(command.version());
        inspector.validate(command.kind(), command.definitionJson());
        if (repository.findSchemaVersion(command.schemaKey(), version.toString()).isPresent()) {
            throw new SchemaRegistryException("SCHEMA_VERSION_CONFLICT", "schema key and version already exist");
        }
        Instant now = clock.instant();
        RegisteredSchema schema = new RegisteredSchema(ids.next(), command.schemaKey(), command.kind(), command.owner(), version,
                RegistryStatus.DRAFT, command.definitionJson(), sha256(command.definitionJson()),
                1L, command.effectiveAt() == null ? now : command.effectiveAt(), now, now, null, null, null, null, null, null);
        return execute(transaction -> {
            repository.insertSchema(schema);
            transaction.append(event("rsot.schema.created.v1", schema.id(), context, now, schemaPayload(schema)));
            auditMutation(context, "rsot.schema.create", "rsot_schema", schema.id(), schema.version().toString(), schema.status(), schema.checksumSha256());
            return schema;
        });
    }

    public RegisteredSchema updateDraft(
            DomainIdentifier schemaId, long expectedRevision, String definitionJson, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(context, "context");
        RegisteredSchema current = requireSchema(schemaId);
        requireRevision(current.revision(), expectedRevision);
        inspector.validate(current.kind(), definitionJson);
        RegisteredSchema updated = current.updateDraft(definitionJson, sha256(definitionJson), clock.instant());
        return execute(transaction -> {
            repository.updateDraftSchema(updated);
            transaction.append(event("rsot.schema.updated.v1", updated.id(), context, updated.updatedAt(), schemaPayload(updated)));
            auditMutation(context, "rsot.schema.update", "rsot_schema", updated.id(), updated.version().toString(), updated.status(), updated.checksumSha256());
            return updated;
        });
    }

    public CompatibilityReport previewCompatibility(DomainIdentifier schemaId) {
        features.requireAvailable();
        RegisteredSchema candidate = requireSchema(schemaId);
        if (candidate.status() != RegistryStatus.DRAFT) {
            throw new SchemaRegistryException("SCHEMA_NOT_DRAFT", "compatibility preview requires a draft schema");
        }
        return previousPublished(candidate).map(previous -> inspector.compare(previous.definitionJson(), candidate.definitionJson()))
                .orElseGet(CompatibilityReport::compatible);
    }

    public RegisteredSchema publish(
            DomainIdentifier schemaId, long expectedRevision, String breakingApprovalReference, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(context, "context");
        RegisteredSchema candidate = requireSchema(schemaId);
        requireRevision(candidate.revision(), expectedRevision);
        inspector.validate(candidate.kind(), candidate.definitionJson());
        CompatibilityReport report = previousPublished(candidate)
                .map(previous -> inspector.compare(previous.definitionJson(), candidate.definitionJson()))
                .orElseGet(CompatibilityReport::compatible);
        String evidence = report.verdict().name() + (report.issues().isEmpty() ? "" : ": " + String.join(" | ", report.issues()));
        RegisteredSchema published = candidate.publish(clock.instant(), report, evidence, breakingApprovalReference);
        return execute(transaction -> {
            repository.publishSchema(published);
            transaction.append(event("rsot.schema.published.v1", published.id(), context, published.publishedAt(), schemaPayload(published)));
            auditMutation(context, "rsot.schema.publish", "rsot_schema", published.id(), published.version().toString(), published.status(), published.checksumSha256());
            return published;
        });
    }

    public RegisteredSchema deprecate(
            DomainIdentifier schemaId, long expectedRevision, Instant sunsetAt, String reason, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(context, "context");
        RegisteredSchema current = requireSchema(schemaId);
        requireRevision(current.revision(), expectedRevision);
        RegisteredSchema deprecated = current.deprecate(clock.instant(), sunsetAt, reason);
        return execute(transaction -> {
            repository.deprecateSchema(deprecated);
            transaction.append(event("rsot.schema.deprecated.v1", deprecated.id(), context, deprecated.deprecatedAt(), schemaPayload(deprecated)));
            auditMutation(context, "rsot.schema.deprecate", "rsot_schema", deprecated.id(), deprecated.version().toString(), deprecated.status(), deprecated.checksumSha256());
            return deprecated;
        });
    }

    public RegisteredSchema getSchema(DomainIdentifier id) {
        features.requireAvailable();
        return requireSchema(id);
    }

    public List<RegisteredSchema> listSchemas(
            String schemaKey, SchemaKind kind, RegistryStatus status, int offset, int limit) {
        features.requireAvailable();
        validatePage(offset, limit);
        return repository.listSchemas(schemaKey, kind, status, offset, limit);
    }

    public SchemaProfile createProfile(CreateProfileCommand command, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ContractVersion version = ContractVersion.parse(command.version());
        if (repository.findProfileVersion(command.code(), version.toString()).isPresent()) {
            throw new SchemaRegistryException("SCHEMA_PROFILE_VERSION_CONFLICT", "profile code and version already exist");
        }
        List<DomainIdentifier> schemaIds = List.copyOf(Objects.requireNonNull(command.schemaIds(), "schemaIds"));
        List<SchemaProfileMember> members = java.util.stream.IntStream.range(0, schemaIds.size())
                .mapToObj(index -> new SchemaProfileMember(index + 1, schemaIds.get(index), true)).toList();
        for (SchemaProfileMember member : members) {
            RegisteredSchema schema = requireSchema(member.schemaId());
            if (schema.status() != RegistryStatus.PUBLISHED) {
                throw new SchemaRegistryException("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", "profile members must be published schemas");
            }
        }
        Instant now = clock.instant();
        SchemaProfile profile = new SchemaProfile(ids.next(), command.code(), command.owner(), version, RegistryStatus.DRAFT,
                members, profileChecksum(command.code(), version.toString(), members), 1L, now, now, null, null, null, null);
        return execute(transaction -> {
            repository.insertProfile(profile);
            transaction.append(event("rsot.schema.profile.created.v1", profile.id(), context, now, profilePayload(profile)));
            auditMutation(context, "rsot.schema.profile.create", "rsot_schema_profile", profile.id(), profile.version().toString(), profile.status(), profile.checksumSha256());
            return profile;
        });
    }

    public SchemaProfile publishProfile(DomainIdentifier profileId, long expectedRevision, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(context, "context");
        SchemaProfile current = requireProfile(profileId);
        requireRevision(current.revision(), expectedRevision);
        for (SchemaProfileMember member : current.members()) {
            if (requireSchema(member.schemaId()).status() != RegistryStatus.PUBLISHED) {
                throw new SchemaRegistryException("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", "profile members must remain published at activation time");
            }
        }
        SchemaProfile published = current.publish(clock.instant());
        return execute(transaction -> {
            repository.publishProfile(published);
            transaction.append(event("rsot.schema.profile.published.v1", published.id(), context, published.publishedAt(), profilePayload(published)));
            auditMutation(context, "rsot.schema.profile.publish", "rsot_schema_profile", published.id(), published.version().toString(), published.status(), published.checksumSha256());
            return published;
        });
    }

    public SchemaProfile deprecateProfile(
            DomainIdentifier profileId, long expectedRevision, Instant sunsetAt, String reason, SchemaRegistryCommandContext context) {
        features.requireAvailable();
        Objects.requireNonNull(context, "context");
        SchemaProfile current = requireProfile(profileId);
        requireRevision(current.revision(), expectedRevision);
        SchemaProfile deprecated = current.deprecate(clock.instant(), sunsetAt, reason);
        return execute(transaction -> {
            repository.deprecateProfile(deprecated);
            transaction.append(event("rsot.schema.profile.deprecated.v1", deprecated.id(), context, deprecated.deprecatedAt(), profilePayload(deprecated)));
            auditMutation(context, "rsot.schema.profile.deprecate", "rsot_schema_profile", deprecated.id(), deprecated.version().toString(), deprecated.status(), deprecated.checksumSha256());
            return deprecated;
        });
    }

    public SchemaProfile getProfile(DomainIdentifier id) {
        features.requireAvailable();
        return requireProfile(id);
    }

    public List<SchemaProfile> listProfiles(String code, RegistryStatus status, int offset, int limit) {
        features.requireAvailable();
        validatePage(offset, limit);
        return repository.listProfiles(code, status, offset, limit);
    }

    private java.util.Optional<RegisteredSchema> previousPublished(RegisteredSchema candidate) {
        return repository.latestPublishedSchema(candidate.schemaKey())
                .filter(previous -> !previous.id().equals(candidate.id()));
    }

    private RegisteredSchema requireSchema(DomainIdentifier id) {
        return repository.findSchema(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new SchemaRegistryException("SCHEMA_NOT_FOUND", "schema was not found"));
    }

    private SchemaProfile requireProfile(DomainIdentifier id) {
        return repository.findProfile(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new SchemaRegistryException("SCHEMA_PROFILE_NOT_FOUND", "schema profile was not found"));
    }

    private <T> T execute(TransactionalWork<T> work) {
        try {
            return events.execute(work).value();
        } catch (TransactionExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SchemaRegistryException registry) throw registry;
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            throw failure;
        }
    }

    private void auditMutation(
            SchemaRegistryCommandContext context, String action, String targetType, DomainIdentifier targetId,
            String version, RegistryStatus status, String checksum) {
        audit.append(new AuditEntry(
                ids.next(), AuditScope.platform(), context.actorId().toString(), "USER", action, targetType, targetId.toString(),
                "ALLOW", clock.instant(), context.correlationId(), "SUCCESS", "server:rsot-schema-registry", null, null, null,
                Map.of("schema_version", version, "status", status.name(), "checksum_sha256", checksum), "ELEVATED"));
    }

    private EventEnvelope event(
            String type, DomainIdentifier aggregateId, SchemaRegistryCommandContext context, Instant occurredAt, String payload) {
        return new EventEnvelope(ids.next(), new EventType(type), EVENT_VERSION, occurredAt, SOURCE,
                context.correlationId(), aggregateId, payload);
    }

    private static String schemaPayload(RegisteredSchema schema) {
        return "{\"schema_id\":\"" + schema.id() + "\",\"schema_key\":\"" + escape(schema.schemaKey())
                + "\",\"version\":\"" + schema.version() + "\",\"status\":\"" + schema.status()
                + "\",\"checksum_sha256\":\"" + schema.checksumSha256() + "\"}";
    }

    private static String profilePayload(SchemaProfile profile) {
        return "{\"profile_id\":\"" + profile.id() + "\",\"code\":\"" + escape(profile.code())
                + "\",\"version\":\"" + profile.version() + "\",\"status\":\"" + profile.status()
                + "\",\"checksum_sha256\":\"" + profile.checksumSha256() + "\"}";
    }

    private static String profileChecksum(String code, String version, List<SchemaProfileMember> members) {
        StringBuilder canonical = new StringBuilder(code).append('\n').append(version);
        for (SchemaProfileMember member : members) canonical.append('\n').append(member.position()).append(':').append(member.schemaId());
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireRevision(long current, long expected) {
        if (expected < 1) throw new IllegalArgumentException("expected revision must be positive");
        if (current != expected) {
            throw new SchemaRegistryException("SCHEMA_REVISION_CONFLICT", "registry revision changed");
        }
    }

    private static void validatePage(int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
