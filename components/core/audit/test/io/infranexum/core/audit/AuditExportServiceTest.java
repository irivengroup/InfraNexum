package io.infranexum.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AuditExportServiceTest {
    private InMemoryAppendOnlyAuditJournal journal;
    private AuditScope scope;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        journal = new InMemoryAppendOnlyAuditJournal();
        scope = AuditScope.organization("org-1");
        journal.append(AuditModelTest.entry(1, scope, Map.of("operation", "create")));
        journal.append(AuditModelTest.entry(2, scope, Map.of("operation", "review")));
        journal.append(AuditModelTest.entry(3, scope, Map.of("operation", "export")));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
    }

    @Test
    void createsDeterministicSignedExportAndVerifiesIt() throws Exception {
        AuditExportService service = new AuditExportService(journal);
        SignedAuditExport first = service.export(scope, 1, 3, 10, "audit-signing-2026", keyPair.getPrivate());
        SignedAuditExport second = service.export(scope, 1, 3, 10, "audit-signing-2026", keyPair.getPrivate());
        assertEquals(first, second);
        assertArrayEquals(first.archive(), second.archive());
        assertEquals(AuditCanonicalizer.sha256(first.payload()), first.manifest().payloadSha256());
        assertEquals(3, first.manifest().entryCount());
        assertEquals(3, first.manifest().lastSequence());
        assertEquals("2026-08-10T08:00:03Z", first.manifest().snapshotAt().toString());
        assertTrue(new AuditExportVerifier().verify(first, keyPair.getPublic()));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.archive()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
        }
        assertEquals(List.of("audit.jsonl", "manifest.properties", "signature.ed25519.b64"), names);
        assertTrue(new String(first.payload(), StandardCharsets.UTF_8).contains("\"authorizationDecision\":\"ALLOW\""));
    }

    @Test
    void rejectsInvalidExportInputsAndTampering() throws Exception {
        AuditExportService service = new AuditExportService(journal);
        assertThrows(IllegalArgumentException.class, () -> service.export(scope, 4, 4, 1, "key", keyPair.getPrivate()));
        assertThrows(IllegalStateException.class, () -> service.export(scope, 1, 3, 2, "key", keyPair.getPrivate()));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertThrows(IllegalArgumentException.class, () -> service.export(scope, 1, 3, 3, "key", rsa.generateKeyPair().getPrivate()));

        SignedAuditExport original = service.export(scope, 1, 3, 3, "key", keyPair.getPrivate());
        byte[] tamperedPayload = original.payload();
        tamperedPayload[0] ^= 1;
        SignedAuditExport tampered = new SignedAuditExport(original.manifest(), tamperedPayload, original.signature(), original.archive());
        assertFalse(new AuditExportVerifier().verify(tampered, keyPair.getPublic()));

        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(new AuditExportVerifier().verify(original, other.getPublic()));
    }

    @Test
    void validatesManifestAndExportValueObject() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "key", scope, 2, 1, 0, java.time.Instant.EPOCH, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "key", scope, 1, 2, 1, java.time.Instant.EPOCH, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1\n", "key", scope, 1, 1, 1, java.time.Instant.EPOCH, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "key", scope, 1, 1, 1, java.time.Instant.EPOCH, "bad", digest, digest));
        AuditExportManifest manifest = new AuditExportManifest("v1", "key", scope, 1, 1, 1, java.time.Instant.EPOCH, digest, digest, digest);
        assertTrue(manifest.canonicalText().startsWith("entryCount=1\n"));
        assertThrows(IllegalArgumentException.class, () -> new SignedAuditExport(manifest, new byte[0], new byte[]{1}, new byte[]{1}));
    }
}
