package io.infranexum.core.compatibility;

import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dependency-free executable lifecycle smoke for the PGM-06-E03 Core Schema Registry. */
public final class SchemaRegistrySmoke {
    private static final Instant NOW = Instant.parse("2026-08-14T18:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String V1 = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
    private static final String V2_BREAKING = "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"}}}";

    private SchemaRegistrySmoke() {}

    public static void main(String[] args) {
        Repository repository = new Repository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryAppendOnlyAuditJournal audit = new InMemoryAppendOnlyAuditJournal();
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 2, 3, 4}));
        SchemaRegistryService service = new SchemaRegistryService(
                repository,
                new Inspector(),
                events,
                audit,
                ids,
                CLOCK,
                () -> {});
        SchemaRegistryCommandContext context = new SchemaRegistryCommandContext(ids.next(), ids.next());

        RegisteredSchema firstDraft = service.createSchema(
                new CreateSchemaCommand("asset.server", SchemaKind.RSOT_CANONICAL, "team.rsot", "1.0.0", V1, NOW), context);
        require(firstDraft.status() == RegistryStatus.DRAFT && firstDraft.revision() == 1, "first schema draft is invalid");
        RegisteredSchema first = service.publish(firstDraft.id(), 1, null, context);
        require(first.status() == RegistryStatus.PUBLISHED && first.revision() == 2, "first schema publication failed");
        expectCode("SCHEMA_IMMUTABLE", () -> service.updateDraft(first.id(), 2, V1, context));
        expectCode("SCHEMA_REVISION_CONFLICT", () -> service.deprecate(first.id(), 1, NOW.plusSeconds(3600), "retired", context));

        RegisteredSchema secondDraft = service.createSchema(
                new CreateSchemaCommand("asset.server", SchemaKind.RSOT_CANONICAL, "team.rsot", "2.0.0", V2_BREAKING, NOW), context);
        require(service.previewCompatibility(secondDraft.id()).verdict() == CompatibilityVerdict.BREAKING,
                "breaking compatibility was not detected");
        expectCode("SCHEMA_BREAKING_APPROVAL_REQUIRED", () -> service.publish(secondDraft.id(), 1, null, context));
        RegisteredSchema second = service.publish(secondDraft.id(), 1, "ADR-9001", context);
        require("ADR-9001".equals(second.breakingApprovalReference()), "breaking approval was not persisted");

        SchemaProfile profileDraft = service.createProfile(
                new CreateProfileCommand(null, "team.rsot", "1.0.0", List.of(first.id(), second.id())), context);
        SchemaProfile profile = service.publishProfile(profileDraft.id(), 1, context);
        require(profile.status() == RegistryStatus.PUBLISHED && profile.members().size() == 2, "profile publication failed");
        require(profile.code().matches("[a-z][a-z0-9.-]{2,159}"), "generated profile code must preserve RSOT lowercase contract");
        require(profile.code().startsWith("team-rsot-1-0-0-"), "generated profile code must remain memorable");

        RegisteredSchema deprecated = service.deprecate(first.id(), 2, NOW.plusSeconds(7200), "superseded by 2.0.0", context);
        require(deprecated.status() == RegistryStatus.DEPRECATED, "schema deprecation failed");
        expectCode("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", () -> service.createProfile(
                new CreateProfileCommand("server.legacy", "team.rsot", "1.0.0", List.of(first.id())), context));

        require(events.outboxSnapshot().stream().anyMatch(record -> "rsot.schema.published.v1".equals(record.event().eventType().value())),
                "schema publication event was not emitted");
        require(audit.verify(AuditScope.platform()).valid(), "registry audit chain is invalid");
        require(audit.readRange(AuditScope.platform(), 1, 100, 100).size() == 7, "unexpected registry audit cardinality");
        expect(IllegalArgumentException.class, () -> service.listSchemas(null, null, null, 0, 201));

        final int[] gateCalls = {0};
        SchemaRegistryService blocked = new SchemaRegistryService(
                repository, new Inspector(), events, audit, ids, CLOCK, () -> {
                    gateCalls[0]++;
                    throw new IllegalStateException("rsot.core unavailable");
                });
        expect(IllegalStateException.class, () -> blocked.listSchemas(null, null, null, 0, 10));
        require(gateCalls[0] == 1, "capability gate was not invoked exactly once");

        System.out.println("java-schema-registry-smoke: PASS");
    }

    private static final class Inspector implements SchemaDefinitionInspector {
        @Override
        public void validate(SchemaKind kind, String definitionJson) {
            if (kind == null || definitionJson == null || !definitionJson.startsWith("{")) {
                throw new IllegalArgumentException("invalid smoke schema");
            }
        }

        @Override
        public CompatibilityReport compare(String previousDefinitionJson, String candidateDefinitionJson) {
            if (candidateDefinitionJson.contains("\"required\":[\"name\"]")
                    && !previousDefinitionJson.contains("\"required\":[\"name\"]")) {
                return new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of("optional property became required: name"));
            }
            return CompatibilityReport.compatible();
        }
    }

    private static final class Repository implements SchemaRegistryRepository {
        private final Map<DomainIdentifier, RegisteredSchema> schemas = new LinkedHashMap<>();
        private final Map<DomainIdentifier, SchemaProfile> profiles = new LinkedHashMap<>();

        @Override public Optional<RegisteredSchema> findSchema(DomainIdentifier id) { return Optional.ofNullable(schemas.get(id)); }
        @Override public Optional<RegisteredSchema> findSchemaVersion(String key, String version) {
            return schemas.values().stream().filter(value -> value.schemaKey().equals(key) && value.version().toString().equals(version)).findFirst();
        }
        @Override public Optional<RegisteredSchema> latestPublishedSchema(String key) {
            return schemas.values().stream()
                    .filter(value -> value.schemaKey().equals(key) && value.publishedAt() != null)
                    .max(Comparator.comparing(RegisteredSchema::publishedAt).thenComparing(value -> value.version().toString()));
        }
        @Override public List<RegisteredSchema> listSchemas(String key, SchemaKind kind, RegistryStatus status, int offset, int limit) {
            List<RegisteredSchema> values = schemas.values().stream()
                    .filter(value -> key == null || value.schemaKey().equals(key))
                    .filter(value -> kind == null || value.kind() == kind)
                    .filter(value -> status == null || value.status() == status)
                    .toList();
            return page(values, offset, limit);
        }
        @Override public void insertSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void updateDraftSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void publishSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void deprecateSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public Optional<SchemaProfile> findProfile(DomainIdentifier id) { return Optional.ofNullable(profiles.get(id)); }
        @Override public Optional<SchemaProfile> findProfileVersion(String code, String version) {
            return profiles.values().stream().filter(value -> value.code().equals(code) && value.version().toString().equals(version)).findFirst();
        }
        @Override public List<SchemaProfile> listProfiles(String code, RegistryStatus status, int offset, int limit) {
            List<SchemaProfile> values = profiles.values().stream()
                    .filter(value -> code == null || value.code().equals(code))
                    .filter(value -> status == null || value.status() == status)
                    .toList();
            return page(values, offset, limit);
        }
        @Override public void insertProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }
        @Override public void publishProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }
        @Override public void deprecateProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }

        private static <T> List<T> page(List<T> values, int offset, int limit) {
            if (offset >= values.size()) return List.of();
            return new ArrayList<>(values.subList(offset, Math.min(values.size(), offset + limit)));
        }
    }

    private static void expectCode(String code, ThrowingAction action) {
        try {
            action.run();
        } catch (SchemaRegistryException error) {
            require(code.equals(error.code()), "unexpected registry code: " + error.code());
            return;
        } catch (Exception error) {
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected SchemaRegistryException " + code);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            require(type.isInstance(error), "unexpected exception type: " + error);
            return;
        }
        throw new AssertionError("expected exception " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
