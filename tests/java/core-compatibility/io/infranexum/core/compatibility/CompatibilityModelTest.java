package io.infranexum.core.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityModelTest {
    private static final DomainIdentifier ID = DomainIdentifier.parse("01900000-0000-7000-8000-000000000001");
    private static final DomainIdentifier ID2 = DomainIdentifier.parse("01900000-0000-7000-8000-000000000002");
    private static final Instant T = Instant.parse("2026-08-14T19:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void compatibilityReportEnforcesVerdictEvidenceInvariant() {
        assertEquals(CompatibilityVerdict.COMPATIBLE, CompatibilityReport.compatible().verdict());
        assertThrows(IllegalArgumentException.class, () -> new CompatibilityReport(CompatibilityVerdict.COMPATIBLE, List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new CompatibilityReport(CompatibilityVerdict.BREAKING, List.of()));
        assertThrows(NullPointerException.class, () -> new CompatibilityReport(null, List.of()));
        assertThrows(NullPointerException.class, () -> new CompatibilityReport(CompatibilityVerdict.BREAKING, null));
    }

    @Test
    void schemaModelRejectsMalformedIdentityContentAndLifecycle() {
        RegisteredSchema draft = schema(RegistryStatus.DRAFT, 1, null, null, null, null);
        assertEquals("rsot.router", draft.schemaKey());
        assertEquals("team.rsot", draft.owner());
        assertEquals("{}", draft.definitionJson());
        assertThrows(IllegalArgumentException.class, () -> schemaWith("x", "team.rsot", "{}", HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("rsot.router", "x", "{}", HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("rsot.router", "team.rsot", "[]", HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("rsot.router", "team.rsot", "{}", "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> schemaWith("rsot.router", "team.rsot", "{}", HASH, 0));
        assertThrows(IllegalArgumentException.class, () -> new RegisteredSchema(ID, "rsot.router", SchemaKind.API, "team.rsot",
                ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT, "{}", HASH, 1, T, T, T.minusSeconds(1), null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DRAFT, 1, T, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.PUBLISHED, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> schema(RegistryStatus.DEPRECATED, 1, T, null, null, null));
        assertThrows(SchemaRegistryException.class, () -> draft.deprecate(T, T.plusSeconds(1), "x"));
        assertThrows(IllegalArgumentException.class, () -> draft.publish(T, CompatibilityReport.compatible(), "ok", null).deprecate(T, T.plusSeconds(1), " "));
    }

    @Test
    void profileModelRejectsMalformedCompositionAndLifecycle() {
        SchemaProfileMember first = new SchemaProfileMember(1, ID, true);
        assertThrows(IllegalArgumentException.class, () -> new SchemaProfileMember(0, ID, true));
        assertThrows(NullPointerException.class, () -> new SchemaProfileMember(1, null, true));
        assertThrows(IllegalArgumentException.class, () -> profile("x", List.of(first), RegistryStatus.DRAFT, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(), RegistryStatus.DRAFT, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(new SchemaProfileMember(2, ID, true)), RegistryStatus.DRAFT, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(first, new SchemaProfileMember(2, ID, true)), RegistryStatus.DRAFT, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SchemaProfile(ID, "rsot.network", "team.rsot", ContractVersion.parse("1.0.0"),
                RegistryStatus.DRAFT, List.of(first), "bad", 1, T, T, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(first), RegistryStatus.DRAFT, 0, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SchemaProfile(ID, "rsot.network", "team.rsot", ContractVersion.parse("1.0.0"),
                RegistryStatus.DRAFT, List.of(first), HASH, 1, T, T.minusSeconds(1), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(first), RegistryStatus.DRAFT, 1, T, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(first), RegistryStatus.PUBLISHED, 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> profile("rsot.network", List.of(first), RegistryStatus.DEPRECATED, 1, T, null, null, null));

        SchemaProfile draft = profile("rsot.network", List.of(first), RegistryStatus.DRAFT, 1, null, null, null, null);
        SchemaProfile published = draft.publish(T);
        assertEquals(2, published.revision());
        assertThrows(SchemaRegistryException.class, () -> published.publish(T));
        assertThrows(IllegalArgumentException.class, () -> published.deprecate(T, T, "x"));
        assertThrows(IllegalArgumentException.class, () -> published.deprecate(T, T.plusSeconds(1), " "));
        SchemaProfile deprecated = published.deprecate(T, T.plusSeconds(1), "retired");
        assertEquals(RegistryStatus.DEPRECATED, deprecated.status());
        assertEquals(3, deprecated.revision());
    }

    @Test
    void enumAndExceptionContractsRemainStable() {
        assertEquals(9, SchemaKind.values().length);
        assertEquals(3, RegistryStatus.values().length);
        assertEquals(3, CompatibilityVerdict.values().length);
        SchemaRegistryException error = new SchemaRegistryException("SCHEMA_TEST", "message");
        assertEquals("SCHEMA_TEST", error.code());
        assertEquals("message", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryException("bad code", "message"));
    }

    private static RegisteredSchema schema(
            RegistryStatus status, long revision, Instant published, Instant deprecated, Instant sunset, String reason) {
        return new RegisteredSchema(ID, "RSOT.ROUTER", SchemaKind.RSOT_EXTENSION, "TEAM.RSOT", ContractVersion.parse("1.0.0"),
                status, " {} ", HASH.toUpperCase(), revision, T, T, T, published, deprecated, sunset, reason, null, null);
    }

    private static RegisteredSchema schemaWith(String key, String owner, String json, String hash, long revision) {
        return new RegisteredSchema(ID, key, SchemaKind.API, owner, ContractVersion.parse("1.0.0"), RegistryStatus.DRAFT,
                json, hash, revision, T, T, T, null, null, null, null, null, null);
    }

    private static SchemaProfile profile(
            String code, List<SchemaProfileMember> members, RegistryStatus status, long revision,
            Instant published, Instant deprecated, Instant sunset, String reason) {
        return new SchemaProfile(ID2, code, "TEAM.RSOT", ContractVersion.parse("1.0.0"), status, members, HASH.toUpperCase(),
                revision, T, T, published, deprecated, sunset, reason);
    }
}
