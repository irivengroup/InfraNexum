package io.infranexum.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuditModelTest {
    @Test
    void normalizesScopeEntryAndMetadata() {
        AuditScope scope = new AuditScope(" organization ", " org-1 ");
        AuditEntry entry = entry(1, scope, Map.of("zone", " west ", "count", "2"));
        assertEquals("ORGANIZATION", scope.type());
        assertEquals("org-1", scope.id());
        assertEquals("ALLOW", entry.authorizationDecision());
        assertEquals("SUCCESS", entry.result());
        assertEquals("west", entry.metadata().get("zone"));
        assertThrows(UnsupportedOperationException.class, () -> entry.metadata().put("x", "y"));
        assertEquals(AuditScope.platform(), new AuditScope("PLATFORM", "platform"));
        assertEquals(AuditScope.organization("org-1"), scope);
        assertTrue(AuditScope.platform().compareTo(scope) > 0);
        assertEquals(0, scope.compareTo(AuditScope.organization("org-1")));
    }

    @Test
    void rejectsInvalidScopeAndEntryValues() {
        assertThrows(NullPointerException.class, () -> new AuditScope(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new AuditScope("x", "x"));
        assertThrows(IllegalArgumentException.class, () -> new AuditScope("ORGANIZATION", " "));
        assertThrows(NullPointerException.class, () -> entryWith(null, "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), " ", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "bad action!", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "allow-now", Instant.EPOCH, "SUCCESS", "api", Map.of(), "INTERNAL"));
        assertThrows(NullPointerException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", null, "SUCCESS", "api", Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", " ", Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", Map.of("password", "x"), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", Map.of("safe", "x".repeat(1025)), "INTERNAL"));
        Map<String, String> large = new HashMap<>();
        for (int i = 0; i < 5; i++) large.put("key" + i, "x".repeat(900));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api", large, "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> entryWith(id(2), "actor", "USER", "iam.role.create", "ROLE", "r1", "ALLOW", Instant.EPOCH, "SUCCESS", "api\nmalicious", Map.of(), "INTERNAL"));
    }

    @Test
    void validatesRecordVerificationAndPurgeTombstone() {
        AuditEntry entry = entry(3, AuditScope.platform(), Map.of());
        String hash = AuditCanonicalizer.hash(1, AuditCanonicalizer.GENESIS_HASH, entry);
        AuditRecord record = new AuditRecord(1, entry, AuditCanonicalizer.GENESIS_HASH, hash);
        assertEquals(hash, record.entryHash());
        assertThrows(IllegalArgumentException.class, () -> new AuditRecord(0, entry, AuditCanonicalizer.GENESIS_HASH, hash));
        assertThrows(IllegalArgumentException.class, () -> new AuditRecord(1, entry, "bad", hash));
        assertEquals(AuditCanonicalizer.GENESIS_HASH, new AuditChainVerification(true, 0, 0, AuditCanonicalizer.GENESIS_HASH).headHash());
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(true, 1, 1, hash));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(false, 0, 0, hash));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(false, -1, 1, hash));

        AuditPurgeTombstone tombstone = new AuditPurgeTombstone(id(10), AuditScope.organization("org-1"), "RETENTION-1", id(11), id(12), Instant.EPOCH, hash, "legal retention expired");
        assertEquals("RETENTION-1", tombstone.policyId());
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(id(10), AuditScope.platform(), "RETENTION-1", id(11), id(11), Instant.EPOCH, hash, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(id(10), AuditScope.platform(), "RETENTION-1", id(11), id(12), Instant.EPOCH, "bad", "reason"));
    }

    static AuditEntry entry(int sequence, AuditScope scope, Map<String, String> metadata) {
        return entryWithScope(scope, id(sequence), "user-1", "USER", "iam.role.create", "ROLE", "role-1", "allow", Instant.parse("2026-08-10T08:00:00Z").plusSeconds(sequence), "success", "api/server", metadata, "internal");
    }

    private static AuditEntry entryWith(DomainIdentifier auditId, String actorId, String actorType, String action, String targetType, String targetId, String auth, Instant at, String result, String origin, Map<String, String> metadata, String sensitivity) {
        return entryWithScope(AuditScope.platform(), auditId, actorId, actorType, action, targetType, targetId, auth, at, result, origin, metadata, sensitivity);
    }

    private static AuditEntry entryWithScope(AuditScope scope, DomainIdentifier auditId, String actorId, String actorType, String action, String targetType, String targetId, String auth, Instant at, String result, String origin, Map<String, String> metadata, String sensitivity) {
        return new AuditEntry(auditId, scope, actorId, actorType, action, targetType, targetId, auth, at, id(900), result, origin, null, "192.0.2.1", "test-agent", metadata, sensitivity);
    }

    static DomainIdentifier id(int sequence) {
        return DomainIdentifier.parse("018bcfe5-6800-7000-8000-%012d".formatted(sequence));
    }
}
