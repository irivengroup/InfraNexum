package io.infranexum.core.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exhaustive boundary coverage for compatibility-registry immutable models. */
final class CompatibilityCoverageRegressionTest {
    private static final DomainIdentifier ID = DomainIdentifier.parse("01900000-0000-7000-8000-000000000011");
    private static final DomainIdentifier ID2 = DomainIdentifier.parse("01900000-0000-7000-8000-000000000012");
    private static final Instant T = Instant.parse("2026-08-16T12:00:00Z");
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void registeredSchemaCoversLifecycleAccessorsAndPublicationVerdicts() {
        RegisteredSchema draft = schema(RegistryStatus.DRAFT, null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(ID, draft.id()),
                () -> assertEquals("rsot.router", draft.schemaKey()),
                () -> assertEquals(SchemaKind.RSOT_EXTENSION, draft.kind()),
                () -> assertEquals("team.rsot", draft.owner()),
                () -> assertEquals(ContractVersion.parse("1.2.3"), draft.version()),
                () -> assertEquals(RegistryStatus.DRAFT, draft.status()),
                () -> assertEquals("{}", draft.definitionJson()),
                () -> assertEquals(HASH, draft.checksumSha256()),
                () -> assertEquals(1, draft.revision()),
                () -> assertEquals(T, draft.effectiveAt()),
                () -> assertEquals(T, draft.createdAt()),
                () -> assertEquals(T, draft.updatedAt()),
                () -> assertNull(draft.publishedAt()),
                () -> assertNull(draft.deprecatedAt()),
                () -> assertNull(draft.sunsetAt()),
                () -> assertNull(draft.deprecationReason()),
                () -> assertNull(draft.compatibilityEvidence()),
                () -> assertNull(draft.breakingApprovalReference()));

        RegisteredSchema updated = draft.updateDraft(" {\"v\":2} ", "A".repeat(64), T.plusSeconds(1));
        assertEquals(2, updated.revision());
        assertEquals("{\"v\":2}", updated.definitionJson());
        assertEquals("a".repeat(64), updated.checksumSha256());

        RegisteredSchema compatible = updated.publish(T.plusSeconds(2), CompatibilityReport.compatible(), " evidence ", null);
        assertEquals(RegistryStatus.PUBLISHED, compatible.status());
        assertEquals("evidence", compatible.compatibilityEvidence());
        assertNull(compatible.breakingApprovalReference());
        assertThrows(SchemaRegistryException.class, () -> compatible.updateDraft("{}", HASH, T));

        RegisteredSchema breaking = draft.publish(
                T.plusSeconds(2), new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of("removed field")),
                "manual", "ADR-0042");
        assertEquals("ADR-0042", breaking.breakingApprovalReference());
        assertThrows(SchemaRegistryException.class, () -> draft.publish(
                T, new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of("x")), "e", null));
        assertThrows(SchemaRegistryException.class, () -> draft.publish(
                T, new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of("x")), "e", " "));
        assertThrows(SchemaRegistryException.class, () -> draft.publish(
                T, new CompatibilityReport(CompatibilityVerdict.INDETERMINATE, List.of("unknown")), "e", null));
        assertThrows(NullPointerException.class, () -> draft.publish(T, null, "e", null));
        assertThrows(NullPointerException.class, () -> draft.publish(null, CompatibilityReport.compatible(), "e", null));

        RegisteredSchema deprecated = compatible.deprecate(T.plusSeconds(3), T.plusSeconds(30), " retired ");
        assertEquals(RegistryStatus.DEPRECATED, deprecated.status());
        assertEquals("retired", deprecated.deprecationReason());
        assertEquals(T.plusSeconds(30), deprecated.sunsetAt());
        assertThrows(SchemaRegistryException.class, () -> deprecated.deprecate(T, T.plusSeconds(1), "x"));
        assertThrows(NullPointerException.class, () -> compatible.deprecate(T, null, "x"));
        assertThrows(NullPointerException.class, () -> compatible.deprecate(null, T.plusSeconds(1), "x"));
        assertThrows(IllegalArgumentException.class, () -> compatible.deprecate(T, T, "x"));
        assertThrows(IllegalArgumentException.class, () -> compatible.deprecate(T, T.plusSeconds(1), "\n"));
    }

    @Test
    void registeredSchemaRejectsEveryMalformedScalarAndLifecycleCombination() {
        assertThrows(NullPointerException.class, () -> new RegisteredSchema(null, "abc", SchemaKind.API, "abc", ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT, "{}", HASH, 1, T, T, T, null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new RegisteredSchema(ID, "abc", null, "abc", ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT, "{}", HASH, 1, T, T, T, null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> schemaWith(null, "team.rsot", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("  ", "team.rsot", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("ab", "team.rsot", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("a" + "b".repeat(160), "team.rsot", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc\n", "team.rsot", "{}", HASH));
        assertThrows(NullPointerException.class, () -> schemaWith("abc", null, "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "xy", "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "a" + "b".repeat(160), "{}", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "abc\n", "{}", HASH));
        assertThrows(NullPointerException.class, () -> schemaWith("abc", "team.rsot", null, HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "team.rsot", "{", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "team.rsot", "[]", HASH));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "team.rsot", "{" + "x".repeat(1_048_576) + "}", HASH));
        assertThrows(NullPointerException.class, () -> schemaWith("abc", "team.rsot", "{}", null));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "team.rsot", "{}", "g".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("abc", "team.rsot", "{}", "a".repeat(63)));
        assertThrows(NullPointerException.class, () -> new RegisteredSchema(ID, "abc", SchemaKind.API, "abc", null, RegistryStatus.DRAFT, "{}", HASH, 1, T, T, T, null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new RegisteredSchema(ID, "abc", SchemaKind.API, "abc", ContractVersion.parse("1.0.0"), null, "{}", HASH, 1, T, T, T, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RegisteredSchema(ID, "abc", SchemaKind.API, "abc", ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT, "{}", HASH, 0, T, T, T, null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new RegisteredSchema(ID, "abc", SchemaKind.API, "abc", ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT, "{}", HASH, 1, null, T, T, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DRAFT, T, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.PUBLISHED, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DEPRECATED, T, null, T.plusSeconds(1), "x", null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DEPRECATED, T, T.plusSeconds(1), null, "x", null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DEPRECATED, T, T.plusSeconds(1), T.plusSeconds(2), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DRAFT, null, null, null, null, "x".repeat(4001), null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DRAFT, null, null, null, null, "x\n", null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DRAFT, null, null, null, null, null, "x".repeat(241)));
    }

    @Test
    void schemaProfileCoversAllMembersScalarAndLifecycleBranches() {
        SchemaProfileMember first = new SchemaProfileMember(1, ID, true);
        SchemaProfile draft = profile(List.of(first), RegistryStatus.DRAFT, null, null, null, null);
        assertAll(
                () -> assertEquals(ID2, draft.id()), () -> assertEquals("rsot.network", draft.code()),
                () -> assertEquals("team.rsot", draft.owner()), () -> assertEquals(ContractVersion.parse("2.0.0"), draft.version()),
                () -> assertEquals(RegistryStatus.DRAFT, draft.status()), () -> assertEquals(List.of(first), draft.members()),
                () -> assertEquals(HASH, draft.checksumSha256()), () -> assertEquals(1, draft.revision()),
                () -> assertEquals(T, draft.createdAt()), () -> assertEquals(T, draft.updatedAt()),
                () -> assertNull(draft.publishedAt()), () -> assertNull(draft.deprecatedAt()),
                () -> assertNull(draft.sunsetAt()), () -> assertNull(draft.deprecationReason()));
        assertThrows(UnsupportedOperationException.class, () -> draft.members().add(first));
        SchemaProfile published = draft.publish(T.plusSeconds(1));
        assertEquals(2, published.revision());
        assertThrows(SchemaRegistryException.class, () -> published.publish(T));
        assertThrows(NullPointerException.class, () -> draft.publish(null));
        assertThrows(SchemaRegistryException.class, () -> draft.deprecate(T, T.plusSeconds(1), "x"));
        assertThrows(NullPointerException.class, () -> published.deprecate(null, T.plusSeconds(2), "x"));
        assertThrows(NullPointerException.class, () -> published.deprecate(T, null, "x"));
        assertThrows(IllegalArgumentException.class, () -> published.deprecate(T, T.plusSeconds(1), " "));
        assertThrows(IllegalArgumentException.class, () -> published.deprecate(T, T, "x"));
        assertEquals(RegistryStatus.DEPRECATED, published.deprecate(T, T.plusSeconds(1), "done").status());

        assertThrows(NullPointerException.class, () -> profile(null, RegistryStatus.DRAFT, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(), RegistryStatus.DRAFT, null, null, null, null));
        List<SchemaProfileMember> tooMany = new ArrayList<>();
        for (int i = 0; i < 129; i++) tooMany.add(new SchemaProfileMember(i + 1, DomainIdentifier.parse("01900000-0000-7%03x-8000-%012x".formatted(i, i + 100)), true));
        assertThrows(IllegalArgumentException.class, () -> profile(tooMany, RegistryStatus.DRAFT, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(new SchemaProfileMember(2, ID, true)), RegistryStatus.DRAFT, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first, new SchemaProfileMember(2, ID, false)), RegistryStatus.DRAFT, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profileWithCode("ab", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profileWithCode("abc\n", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profileWithOwner("xy", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profileWithOwner("abc\n", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profileWithHash("g".repeat(64), List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profileWithHash("a".repeat(65), List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first), RegistryStatus.DRAFT, T, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first), RegistryStatus.PUBLISHED, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first), RegistryStatus.DEPRECATED, T, null, T.plusSeconds(2), "x"));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first), RegistryStatus.DEPRECATED, T, T.plusSeconds(1), null, "x"));
        assertThrows(IllegalArgumentException.class, () -> profile(List.of(first), RegistryStatus.DEPRECATED, T, T.plusSeconds(1), T.plusSeconds(2), null));
    }

    @Test
    void exceptionAndCommandContextRejectMalformedContracts() {
        SchemaRegistryException error = new SchemaRegistryException("SCHEMA_TEST", " message ");
        assertEquals("SCHEMA_TEST", error.code());
        assertEquals("message", error.getMessage());
        assertThrows(NullPointerException.class, () -> new SchemaRegistryException(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("x", "x"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("BAD-CODE", "x"));
        assertThrows(NullPointerException.class, () -> new SchemaRegistryException("SCHEMA_TEST", null));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("SCHEMA_TEST", " "));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("SCHEMA_TEST", "x\n"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("SCHEMA_TEST", "x".repeat(1025)));
        assertEquals(ID, new SchemaRegistryCommandContext(ID, ID2).actorId());
        assertThrows(NullPointerException.class, () -> new SchemaRegistryCommandContext(null, ID2));
        assertThrows(NullPointerException.class, () -> new SchemaRegistryCommandContext(ID, null));
    }

    private static RegisteredSchema schema(RegistryStatus status, Instant published, Instant deprecated, Instant sunset,
            String reason, String evidence, String approval) {
        return new RegisteredSchema(ID, "RSOT.ROUTER", SchemaKind.RSOT_EXTENSION, "TEAM.RSOT", ContractVersion.parse("1.2.3"),
                status, " {} ", HASH.toUpperCase(), 1, T, T, T, published, deprecated, sunset, reason, evidence, approval);
    }

    private static RegisteredSchema schemaWith(String key, String owner, String json, String hash) {
        return new RegisteredSchema(ID, key, SchemaKind.API, owner, ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT,
                json, hash, 1, T, T, T, null, null, null, null, null, null);
    }

    private static SchemaProfile profile(List<SchemaProfileMember> members, RegistryStatus status, Instant published,
            Instant deprecated, Instant sunset, String reason) {
        return new SchemaProfile(ID2, "RSOT.NETWORK", "TEAM.RSOT", ContractVersion.parse("2.0.0"), status, members,
                HASH.toUpperCase(), 1, T, T, published, deprecated, sunset, reason);
    }

    private static SchemaProfile profileWithCode(String code, List<SchemaProfileMember> members) {
        return new SchemaProfile(ID2, code, "team.rsot", ContractVersion.parse("2.0.0"), RegistryStatus.DRAFT, members,
                HASH, 1, T, T, null, null, null, null);
    }

    private static SchemaProfile profileWithOwner(String owner, List<SchemaProfileMember> members) {
        return new SchemaProfile(ID2, "rsot.network", owner, ContractVersion.parse("2.0.0"), RegistryStatus.DRAFT, members,
                HASH, 1, T, T, null, null, null, null);
    }

    private static SchemaProfile profileWithHash(String hash, List<SchemaProfileMember> members) {
        return new SchemaProfile(ID2, "rsot.network", "team.rsot", ContractVersion.parse("2.0.0"), RegistryStatus.DRAFT, members,
                hash, 1, T, T, null, null, null, null);
    }
}
