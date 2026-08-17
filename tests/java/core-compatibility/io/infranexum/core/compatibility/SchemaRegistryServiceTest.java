package io.infranexum.core.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.audit.InMemoryAppendOnlyAuditJournal;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaRegistryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-14T19:00:00Z");
    private InMemoryRepository repository;
    private StubInspector inspector;
    private InMemoryEventStore events;
    private InMemoryAppendOnlyAuditJournal audit;
    private SchemaRegistryService service;
    private DomainIdentifier actor;
    private SchemaRegistryCommandContext context;
    private boolean capabilityAvailable;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        repository = new InMemoryRepository();
        inspector = new StubInspector();
        events = new InMemoryEventStore();
        audit = new InMemoryAppendOnlyAuditJournal();
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom(new byte[] {1, 2, 3, 4}));
        capabilityAvailable = true;
        service = new SchemaRegistryService(repository, inspector, events, audit, ids, clock,
                () -> { if (!capabilityAvailable) throw new IllegalStateException("rsot.core unavailable"); });
        actor = ids.next();
        context = new SchemaRegistryCommandContext(actor, ids.next());
    }

    @Test
    void schemaLifecycleIsImmutableAuditedVersionedAndOptimisticallyGuarded() {
        RegisteredSchema draft = service.createSchema(command("rsot.server", "1.0.0", "{\"type\":\"object\"}"), context);
        assertEquals(RegistryStatus.DRAFT, draft.status());
        assertEquals(1, draft.revision());
        assertEquals(1, inspector.validations);
        assertEquals(1, events.outboxSnapshot().size());
        assertEquals("rsot.schema.created.v1", events.outboxSnapshot().getFirst().event().eventType().value());
        assertEquals(1, audit.readRange(AuditScope.platform(), 1, 100, 100).size());

        assertThrows(SchemaRegistryException.class, () -> service.createSchema(command("rsot.server", "1.0.0", "{}"), context));
        assertThrows(SchemaRegistryException.class, () -> service.updateDraft(draft.id(), 99, "{}", context));

        RegisteredSchema updated = service.updateDraft(draft.id(), 1, "{\"type\":\"object\",\"x\":1}", context);
        assertEquals(2, updated.revision());
        assertEquals(CompatibilityVerdict.COMPATIBLE, service.previewCompatibility(updated.id()).verdict());

        RegisteredSchema published = service.publish(updated.id(), 2, null, context);
        assertEquals(RegistryStatus.PUBLISHED, published.status());
        assertEquals(3, published.revision());
        assertNotNull(published.publishedAt());
        assertThrows(SchemaRegistryException.class, () -> service.updateDraft(published.id(), 3, "{}", context));
        assertThrows(SchemaRegistryException.class, () -> service.previewCompatibility(published.id()));

        assertThrows(IllegalArgumentException.class,
                () -> service.deprecate(published.id(), 3, NOW, "superseded", context));
        RegisteredSchema deprecated = service.deprecate(published.id(), 3, NOW.plusSeconds(3600), "superseded", context);
        assertEquals(RegistryStatus.DEPRECATED, deprecated.status());
        assertEquals(4, deprecated.revision());
        assertEquals("superseded", deprecated.deprecationReason());
        assertThrows(SchemaRegistryException.class,
                () -> service.deprecate(deprecated.id(), 4, NOW.plusSeconds(7200), "again", context));

        assertEquals(4, events.outboxSnapshot().size());
        assertTrue(audit.verify(AuditScope.platform()).valid());
        assertEquals(4, audit.verify(AuditScope.platform()).verifiedRecords());
    }

    @Test
    void breakingAndIndeterminateCompatibilityAreFailClosed() {
        RegisteredSchema v1 = service.createSchema(command("rsot.router", "1.0.0", "{\"type\":\"object\"}"), context);
        v1 = service.publish(v1.id(), 1, null, context);
        assertEquals(RegistryStatus.PUBLISHED, v1.status());

        RegisteredSchema breaking = service.createSchema(command("rsot.router", "2.0.0", "{\"compat\":\"breaking\"}"), context);
        CompatibilityReport breakingReport = service.previewCompatibility(breaking.id());
        assertEquals(CompatibilityVerdict.BREAKING, breakingReport.verdict());
        SchemaRegistryException approval = assertThrows(SchemaRegistryException.class,
                () -> service.publish(breaking.id(), 1, null, context));
        assertEquals("SCHEMA_BREAKING_APPROVAL_REQUIRED", approval.code());
        RegisteredSchema approved = service.publish(breaking.id(), 1, "ADR-2042", context);
        assertEquals("ADR-2042", approved.breakingApprovalReference());
        assertTrue(approved.compatibilityEvidence().startsWith("BREAKING:"));

        RegisteredSchema indeterminate = service.createSchema(command("rsot.router", "3.0.0", "{\"compat\":\"indeterminate\"}"), context);
        SchemaRegistryException blocked = assertThrows(SchemaRegistryException.class,
                () -> service.publish(indeterminate.id(), 1, null, context));
        assertEquals("SCHEMA_COMPATIBILITY_INDETERMINATE", blocked.code());
    }

    @Test
    void profilesComposeOnlyPublishedSchemasAndHaveIndependentLifecycle() {
        RegisteredSchema schema = service.createSchema(command("rsot.switch", "1.0.0", "{}"), context);
        SchemaRegistryException draftMember = assertThrows(SchemaRegistryException.class,
                () -> service.createProfile(new CreateProfileCommand("rsot.network", "team.rsot", "1.0.0", List.of(schema.id())), context));
        assertEquals("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", draftMember.code());
        RegisteredSchema publishedSchema = service.publish(schema.id(), 1, null, context);

        SchemaProfile profile = service.createProfile(
                new CreateProfileCommand("rsot.network", "team.rsot", "1.0.0", List.of(publishedSchema.id())), context);
        assertEquals(RegistryStatus.DRAFT, profile.status());
        assertEquals(1, profile.members().size());
        assertThrows(SchemaRegistryException.class,
                () -> service.createProfile(new CreateProfileCommand("rsot.network", "team.rsot", "1.0.0", List.of(publishedSchema.id())), context));
        assertThrows(SchemaRegistryException.class, () -> service.publishProfile(profile.id(), 2, context));
        assertThrows(IllegalArgumentException.class, () -> service.publishProfile(profile.id(), 0, context));

        SchemaProfile published = service.publishProfile(profile.id(), 1, context);
        assertEquals(RegistryStatus.PUBLISHED, published.status());
        assertThrows(SchemaRegistryException.class, () -> published.publish(NOW));
        assertThrows(IllegalArgumentException.class, () -> service.deprecateProfile(published.id(), 2, NOW, "too early", context));
        SchemaProfile deprecated = service.deprecateProfile(published.id(), 2, NOW.plusSeconds(7200), "superseded", context);
        assertEquals(RegistryStatus.DEPRECATED, deprecated.status());
        assertThrows(SchemaRegistryException.class,
                () -> service.deprecateProfile(deprecated.id(), 3, NOW.plusSeconds(9000), "again", context));
    }

    @Test
    void profileActivationFailsIfAMemberStopsBeingPublishedAfterDraftCreation() {
        RegisteredSchema schema = service.createSchema(command("rsot.member", "1.0.0", "{}"), context);
        RegisteredSchema publishedSchema = service.publish(schema.id(), 1, null, context);
        SchemaProfile profile = service.createProfile(
                new CreateProfileCommand("rsot.member-profile", "team.rsot", "1.0.0", List.of(publishedSchema.id())), context);
        service.deprecate(publishedSchema.id(), publishedSchema.revision(), NOW.plusSeconds(3600), "retired", context);
        SchemaRegistryException error = assertThrows(SchemaRegistryException.class,
                () -> service.publishProfile(profile.id(), profile.revision(), context));
        assertEquals("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", error.code());
    }

    @Test
    void readsAreBoundedAndCapabilityGateAppliesToEverySurface() {
        RegisteredSchema a = service.createSchema(command("rsot.alpha", "1.0.0", "{}"), context);
        RegisteredSchema b = service.createSchema(command("rsot.beta", "1.0.0", "{}"), context);
        assertEquals(a.id(), service.getSchema(a.id()).id());
        assertEquals(2, service.listSchemas(null, null, RegistryStatus.DRAFT, 0, 2).size());
        assertThrows(IllegalArgumentException.class, () -> service.listSchemas(null, null, null, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.listSchemas(null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listSchemas(null, null, null, 0, 201));
        assertEquals("SCHEMA_NOT_FOUND", assertThrows(SchemaRegistryException.class,
                () -> service.getSchema(context.correlationId())).code());

        b = service.publish(b.id(), 1, null, context);
        SchemaProfile profile = service.createProfile(new CreateProfileCommand("rsot.reads", "team.rsot", "1.0.0", List.of(b.id())), context);
        assertEquals(profile.id(), service.getProfile(profile.id()).id());
        assertEquals(1, service.listProfiles("rsot.reads", RegistryStatus.DRAFT, 0, 50).size());
        assertEquals("SCHEMA_PROFILE_NOT_FOUND", assertThrows(SchemaRegistryException.class,
                () -> service.getProfile(context.correlationId())).code());
        assertThrows(IllegalArgumentException.class, () -> service.listProfiles(null, null, 0, 500));

        capabilityAvailable = false;
        assertThrows(IllegalStateException.class, () -> service.getSchema(a.id()));
        assertThrows(IllegalStateException.class, () -> service.listSchemas(null, null, null, 0, 10));
        assertThrows(IllegalStateException.class, () -> service.getProfile(profile.id()));
        assertThrows(IllegalStateException.class, () -> service.listProfiles(null, null, 0, 10));
    }

    private static CreateSchemaCommand command(String key, String version, String json) {
        return new CreateSchemaCommand(key, SchemaKind.RSOT_CANONICAL, "team.rsot", version, json, null);
    }

    private static final class StubInspector implements SchemaDefinitionInspector {
        int validations;
        @Override public void validate(SchemaKind kind, String definitionJson) {
            assertNotNull(kind); assertNotNull(definitionJson); validations++;
            if (definitionJson.contains("invalid")) throw new IllegalArgumentException("invalid schema");
        }
        @Override public CompatibilityReport compare(String previousDefinitionJson, String candidateDefinitionJson) {
            if (candidateDefinitionJson.contains("breaking")) return new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of("removed property"));
            if (candidateDefinitionJson.contains("indeterminate")) return new CompatibilityReport(CompatibilityVerdict.INDETERMINATE, List.of("complex constraint"));
            return CompatibilityReport.compatible();
        }
    }

    private static final class InMemoryRepository implements SchemaRegistryRepository {
        private final Map<DomainIdentifier, RegisteredSchema> schemas = new LinkedHashMap<>();
        private final Map<DomainIdentifier, SchemaProfile> profiles = new LinkedHashMap<>();
        @Override public Optional<RegisteredSchema> findSchema(DomainIdentifier id) { return Optional.ofNullable(schemas.get(id)); }
        @Override public Optional<RegisteredSchema> findSchemaVersion(String schemaKey, String version) {
            return schemas.values().stream().filter(s -> s.schemaKey().equals(schemaKey) && s.version().toString().equals(version)).findFirst();
        }
        @Override public Optional<RegisteredSchema> latestPublishedSchema(String schemaKey) {
            return schemas.values().stream().filter(s -> s.schemaKey().equals(schemaKey) && s.status() != RegistryStatus.DRAFT)
                    .max(Comparator.comparing(RegisteredSchema::publishedAt));
        }
        @Override public List<RegisteredSchema> listSchemas(String schemaKey, SchemaKind kind, RegistryStatus status, int offset, int limit) {
            List<RegisteredSchema> filtered = schemas.values().stream()
                    .filter(s -> schemaKey == null || schemaKey.isBlank() || s.schemaKey().equals(schemaKey.strip().toLowerCase(Locale.ROOT)))
                    .filter(s -> kind == null || s.kind() == kind).filter(s -> status == null || s.status() == status).toList();
            return filtered.stream().skip(offset).limit(limit).toList();
        }
        @Override public void insertSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void updateDraftSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void publishSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public void deprecateSchema(RegisteredSchema schema) { schemas.put(schema.id(), schema); }
        @Override public Optional<SchemaProfile> findProfile(DomainIdentifier id) { return Optional.ofNullable(profiles.get(id)); }
        @Override public Optional<SchemaProfile> findProfileVersion(String code, String version) {
            return profiles.values().stream().filter(p -> p.code().equals(code) && p.version().toString().equals(version)).findFirst();
        }
        @Override public List<SchemaProfile> listProfiles(String code, RegistryStatus status, int offset, int limit) {
            return profiles.values().stream().filter(p -> code == null || code.isBlank() || p.code().equals(code.strip().toLowerCase(Locale.ROOT)))
                    .filter(p -> status == null || p.status() == status).skip(offset).limit(limit).toList();
        }
        @Override public void insertProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }
        @Override public void publishProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }
        @Override public void deprecateProfile(SchemaProfile profile) { profiles.put(profile.id(), profile); }
    }
}
