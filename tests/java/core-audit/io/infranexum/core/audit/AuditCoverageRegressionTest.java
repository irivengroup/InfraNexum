package io.infranexum.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression coverage for audit validation, cryptographic exports and bounded journal semantics. */
final class AuditCoverageRegressionTest {
    private static final Instant T0 = Instant.parse("2026-08-10T08:00:00Z");

    @Test
    void canonicalizationAndMetadataCoverAllEscapesAndInputGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditCanonicalizer.hash(0, AuditCanonicalizer.GENESIS_HASH, AuditModelTest.entry(1, AuditScope.platform(), Map.of())));
        AuditEntry entry = new AuditEntry(AuditModelTest.id(100), AuditScope.platform(), "actor", "USER",
                "audit.export", "AUDIT", "entry-1", "allow", T0, null, "success", "api/server",
                "quoted \"reason\" \\ path", "192.0.2.10", "agent\\path", Map.of("z", "last", "a", "first"), "internal");
        String canonical = AuditCanonicalizer.canonicalEntry(entry);
        assertTrue(canonical.contains("\\\"reason\\\""));
        assertTrue(canonical.indexOf("\"a\"") < canonical.indexOf("\"z\""));

        Map<String, String> escapes = Map.of("v", "\b\f\n\r\t\"\\/\u0001");
        String json = AuditMetadataJson.encode(escapes);
        assertEquals(escapes, AuditMetadataJson.decode(json));
        assertEquals(Map.of("slash", "/", "backspace", "\b", "formfeed", "\f", "return", "\r"),
                AuditMetadataJson.decode("{\"slash\":\"\\/\",\"backspace\":\"\\b\",\"formfeed\":\"\\f\",\"return\":\"\\r\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"x\":\"\u0001\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{x:\"y\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"x\":\"y\",}"));
    }

    @Test
    void scopeEntryAndTombstoneRejectControlCharactersBeforeNormalization() {
        assertThrows(IllegalArgumentException.class, () -> new AuditScope("PLATFORM\n", "platform"));
        assertThrows(IllegalArgumentException.class, () -> new AuditScope("PLATFORM", "platform\r"));
        assertThrows(NullPointerException.class, () -> AuditScope.platform().compareTo(null));
        assertThrows(IllegalArgumentException.class, () -> new AuditEntry(AuditModelTest.id(101), AuditScope.platform(),
                "actor\n", "USER", "audit.read", "AUDIT", "id", "ALLOW", T0, null, "SUCCESS", "api",
                null, null, null, Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> new AuditEntry(AuditModelTest.id(102), AuditScope.platform(),
                "actor", "USER", "audit.read", "AUDIT", "id", "ALLOW\n", T0, null, "SUCCESS", "api",
                null, null, null, Map.of(), "INTERNAL"));
        assertThrows(IllegalArgumentException.class, () -> new AuditEntry(AuditModelTest.id(103), AuditScope.platform(),
                "actor", "USER", "audit.read", "AUDIT", "id", "ALLOW", T0, null, "SUCCESS", "api\n",
                null, null, null, Map.of(), "INTERNAL"));

        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(AuditModelTest.id(110), AuditScope.platform(),
                "policy\n", AuditModelTest.id(111), AuditModelTest.id(112), T0, digest, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(AuditModelTest.id(110), AuditScope.platform(),
                "policy", AuditModelTest.id(111), AuditModelTest.id(112), T0, digest, "reason\r"));
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(AuditModelTest.id(110), AuditScope.platform(),
                "x".repeat(161), AuditModelTest.id(111), AuditModelTest.id(112), T0, digest, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(AuditModelTest.id(110), AuditScope.platform(),
                "policy", AuditModelTest.id(111), AuditModelTest.id(112), T0, digest, "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> new AuditPurgeTombstone(AuditModelTest.id(110), AuditScope.platform(),
                "   ", AuditModelTest.id(111), AuditModelTest.id(112), T0, digest, "reason"));
    }

    @Test
    void journalRejectsInvalidInputsAndPreservesGlobalAuditIdentity() {
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        AuditScope firstScope = AuditScope.organization("one");
        AuditEntry first = AuditModelTest.entry(120, firstScope, Map.of());
        journal.append(first);
        assertThrows(IllegalArgumentException.class, () -> journal.append(new AuditEntry(first.auditId(), AuditScope.organization("two"),
                first.actorId(), first.actorType(), first.action(), first.targetType(), first.targetId(), first.authorizationDecision(),
                first.timestamp(), first.correlationId(), first.result(), first.origin(), first.reason(), first.clientIp(), first.userAgent(),
                first.metadata(), first.sensitivity())));
        assertThrows(NullPointerException.class, () -> journal.append(null));
        assertThrows(NullPointerException.class, () -> journal.readRange(null, 1, 1, 1));
        assertThrows(NullPointerException.class, () -> journal.verify(null));
        journal.append(AuditModelTest.entry(121, firstScope, Map.of()));
        assertEquals(1, journal.readRange(firstScope, 1, 2, 1).size());
    }

    @Test
    void auditRecordsAndVerificationResultsEnforceAllInvariants() {
        AuditEntry entry = AuditModelTest.entry(130, AuditScope.platform(), Map.of());
        String hash = AuditCanonicalizer.hash(1, AuditCanonicalizer.GENESIS_HASH, entry);
        assertThrows(NullPointerException.class, () -> new AuditRecord(1, null, AuditCanonicalizer.GENESIS_HASH, hash));
        assertThrows(NullPointerException.class, () -> new AuditRecord(1, entry, null, hash));
        assertThrows(NullPointerException.class, () -> new AuditRecord(1, entry, AuditCanonicalizer.GENESIS_HASH, null));
        assertThrows(IllegalArgumentException.class, () -> new AuditRecord(1, entry, "A".repeat(64), hash));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(true, 0, 0, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(false, 0, 0, hash));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(true, 0, 1, hash));
    }

    @Test
    void signedExportValueObjectIsDefensiveAndVerifierFailsClosed() throws Exception {
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        AuditScope scope = AuditScope.organization("export");
        journal.append(AuditModelTest.entry(140, scope, Map.of()));
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SignedAuditExport export = new AuditExportService(journal).export(scope, 1, 1, 1, "key", keyPair.getPrivate());
        byte[] payload = export.payload(); payload[0] ^= 1;
        assertFalse(java.util.Arrays.equals(payload, export.payload()));
        byte[] signature = export.signature(); signature[0] ^= 1;
        assertFalse(java.util.Arrays.equals(signature, export.signature()));
        byte[] archive = export.archive(); archive[0] ^= 1;
        assertFalse(java.util.Arrays.equals(archive, export.archive()));
        assertEquals(java.util.Base64.getEncoder().encodeToString(export.signature()), export.signatureBase64());
        assertTrue(export.equals(export));
        assertFalse(export.equals("not-an-export"));
        assertEquals(export.hashCode(), new SignedAuditExport(export.manifest(), export.payload(), export.signature(), export.archive()).hashCode());
        assertThrows(NullPointerException.class, () -> new AuditExportVerifier().verify(null, keyPair.getPublic()));
        assertThrows(NullPointerException.class, () -> new AuditExportVerifier().verify(export, null));
        var rsa = KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        assertFalse(new AuditExportVerifier().verify(export, rsa.generateKeyPair().getPublic()));
    }

    @Test
    void exportServiceDetectsDiscontinuityAndHashTampering() throws Exception {
        AuditScope scope = AuditScope.organization("slice");
        AuditEntry firstEntry = AuditModelTest.entry(150, scope, Map.of());
        String firstHash = AuditCanonicalizer.hash(1, AuditCanonicalizer.GENESIS_HASH, firstEntry);
        AuditRecord first = new AuditRecord(1, firstEntry, AuditCanonicalizer.GENESIS_HASH, firstHash);
        AuditEntry secondEntry = AuditModelTest.entry(151, scope, Map.of());
        String secondHash = AuditCanonicalizer.hash(2, firstHash, secondEntry);
        AuditRecord second = new AuditRecord(2, secondEntry, firstHash, secondHash);
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();

        AuditJournal discontinuous = fixedJournal(List.of(first, new AuditRecord(3, secondEntry, firstHash,
                AuditCanonicalizer.hash(3, firstHash, secondEntry))));
        assertThrows(IllegalStateException.class, () -> new AuditExportService(discontinuous).export(scope, 1, 3, 3, "key", key));

        AuditRecord badHash = new AuditRecord(2, secondEntry, firstHash, "f".repeat(64));
        assertThrows(IllegalStateException.class, () -> new AuditExportService(fixedJournal(List.of(first, badHash)))
                .export(scope, 1, 2, 2, "key", key));
        assertThrows(NullPointerException.class, () -> new AuditExportService(null));
        assertThrows(NullPointerException.class, () -> new AuditExportService(fixedJournal(List.of(first))).export(null, 1, 1, 1, "key", key));
        assertThrows(NullPointerException.class, () -> new AuditExportService(fixedJournal(List.of(first))).export(scope, 1, 1, 1, "key", null));
    }

    @Test
    void manifestRejectsCanonicalTextInjectionAndInvalidFields() {
        String digest = "a".repeat(64);
        AuditScope scope = AuditScope.platform();
        assertThrows(NullPointerException.class, () -> new AuditExportManifest(null, "key", scope, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "key=value", scope, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "key\r", scope, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditExportManifest("v1", "x".repeat(161), scope, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(NullPointerException.class, () -> new AuditExportManifest("v1", "key", null, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(NullPointerException.class, () -> new AuditExportManifest("v1", "key", scope, 1, 1, 1, null, digest, digest, digest));
        AuditExportManifest manifest = new AuditExportManifest(" v1 ", " key ", scope, 1, 1, 1, T0, digest, digest, digest);
        assertTrue(manifest.canonicalText().getBytes(StandardCharsets.UTF_8).length > 0);
    }


    @Test
    void auditBoundaryBranchesRemainFailClosed() throws Exception {
        String digest = "a".repeat(64);
        AuditScope scope = AuditScope.platform();
        AuditEntry entry = AuditModelTest.entry(170, scope, Map.of());

        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(true, -1, 0, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(false, 0, -1, digest));
        assertThrows(IllegalArgumentException.class, () -> new AuditChainVerification(true, 0, 0, null));

        assertThrows(NullPointerException.class, () -> new AuditEntry(AuditModelTest.id(171), scope,
                "actor", "USER", "audit.read", "AUDIT", "id", "ALLOW", T0, null, "SUCCESS", null,
                null, null, null, Map.of(), "INTERNAL"));
        AuditEntry optionalBlank = new AuditEntry(AuditModelTest.id(172), scope,
                "actor", "USER", "audit.read", "AUDIT", "id", "ALLOW", T0, null, "SUCCESS", "api",
                "   ", "   ", "   ", Map.of(), "INTERNAL");
        assertNull(optionalBlank.reason());
        assertNull(optionalBlank.clientIp());
        assertNull(optionalBlank.userAgent());
        assertThrows(IllegalArgumentException.class, () -> new AuditEntry(AuditModelTest.id(173), scope,
                "actor", "USER", "audit.read", "AUDIT", "id", "ALLOW", T0, null, "SUCCESS", "x".repeat(513),
                null, null, null, Map.of(), "INTERNAL"));

        assertThrows(IllegalArgumentException.class,
                () -> new AuditExportManifest("v1", "key", scope, 0, 1, 2, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditExportManifest("v1", "key", scope, 2, 1, 0, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditExportManifest("v1", "key", scope, 1, 2, 1, T0, digest, digest, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditExportManifest("   ", "key", scope, 1, 1, 1, T0, digest, digest, digest));
        assertThrows(NullPointerException.class,
                () -> new AuditExportManifest("v1", "key", scope, 1, 1, 1, T0, null, digest, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditExportManifest("v1", "key", scope, 1, 1, 1, T0, "A".repeat(64), digest, digest));

        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        journal.append(entry);
        journal.append(AuditModelTest.entry(174, scope, Map.of()));
        assertEquals(1, journal.readRange(scope, 2, 99, 99).size());
        assertEquals(1, journal.readRange(scope, 1, 1, 99).size());
        assertTrue(new InMemoryAppendOnlyAuditJournal().verify(scope).valid());
    }

    @Test
    void exportAndSignedValueObjectCoverFailureBranches() throws Exception {
        AuditScope scope = AuditScope.organization("export-branches");
        AuditEntry firstEntry = AuditModelTest.entry(180, scope, Map.of());
        String firstHash = AuditCanonicalizer.hash(1, AuditCanonicalizer.GENESIS_HASH, firstEntry);
        AuditRecord first = new AuditRecord(1, firstEntry, AuditCanonicalizer.GENESIS_HASH, firstHash);
        var realPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        AuditEntry secondEntry = AuditModelTest.entry(181, scope, Map.of());
        AuditRecord wrongPrevious = new AuditRecord(2, secondEntry, "b".repeat(64),
                AuditCanonicalizer.hash(2, "b".repeat(64), secondEntry));
        assertThrows(IllegalStateException.class, () -> new AuditExportService(fixedJournal(List.of(first, wrongPrevious)))
                .export(scope, 1, 2, 2, "key", realPair.getPrivate()));
        AuditEntry thirdEntry = AuditModelTest.entry(182, scope, Map.of());
        AuditRecord skippedSequence = new AuditRecord(3, secondEntry, firstHash,
                AuditCanonicalizer.hash(3, firstHash, secondEntry));
        AuditRecord finalThird = new AuditRecord(3, thirdEntry, skippedSequence.entryHash(),
                AuditCanonicalizer.hash(3, skippedSequence.entryHash(), thirdEntry));
        assertThrows(IllegalStateException.class, () -> new AuditExportService(fixedJournal(List.of(first, skippedSequence, finalThird)))
                .export(scope, 1, 3, 3, "key", realPair.getPrivate()));
        assertThrows(IllegalStateException.class, () -> new AuditExportService(fixedJournal(List.of(first)))
                .export(scope, 1, 2, 2, "key", realPair.getPrivate()));

        java.security.PrivateKey invalidEd25519 = new java.security.PrivateKey() {
            @Override public String getAlgorithm() { return "Ed25519"; }
            @Override public String getFormat() { return "PKCS#8"; }
            @Override public byte[] getEncoded() { return new byte[] {1, 2, 3}; }
        };
        assertThrows(IllegalStateException.class, () -> new AuditExportService(fixedJournal(List.of(first)))
                .export(scope, 1, 1, 1, "key", invalidEd25519));

        SignedAuditExport base = new AuditExportService(fixedJournal(List.of(first)))
                .export(scope, 1, 1, 1, "key", realPair.getPrivate());
        assertThrows(NullPointerException.class, () -> new SignedAuditExport(null, new byte[]{1}, new byte[]{1}, new byte[]{1}));
        assertThrows(NullPointerException.class, () -> new SignedAuditExport(base.manifest(), null, new byte[]{1}, new byte[]{1}));
        assertThrows(NullPointerException.class, () -> new SignedAuditExport(base.manifest(), new byte[]{1}, null, new byte[]{1}));
        assertThrows(NullPointerException.class, () -> new SignedAuditExport(base.manifest(), new byte[]{1}, new byte[]{1}, null));
        assertThrows(IllegalArgumentException.class, () -> new SignedAuditExport(base.manifest(), new byte[]{1}, new byte[0], new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new SignedAuditExport(base.manifest(), new byte[]{1}, new byte[]{1}, new byte[0]));

        SignedAuditExport changedPayload = new SignedAuditExport(base.manifest(), new byte[]{9}, base.signature(), base.archive());
        SignedAuditExport changedSignature = new SignedAuditExport(base.manifest(), base.payload(), new byte[]{9}, base.archive());
        SignedAuditExport changedArchive = new SignedAuditExport(base.manifest(), base.payload(), base.signature(), new byte[]{9});
        AuditExportManifest otherManifest = new AuditExportManifest("v1", "other", scope, 1, 1, 1, T0,
                "a".repeat(64), AuditCanonicalizer.GENESIS_HASH, firstHash);
        SignedAuditExport changedManifest = new SignedAuditExport(otherManifest, base.payload(), base.signature(), base.archive());
        assertFalse(base.equals(changedManifest));
        assertFalse(base.equals(changedPayload));
        assertFalse(base.equals(changedSignature));
        assertFalse(base.equals(changedArchive));
    }

    @Test
    void metadataParserCoversTruncationAndEscapeBoundaries() {
        assertEquals(Map.of("x", "\b\f/\r\t"), AuditMetadataJson.decode("{\"x\":\"\\b\\f\\/\\r\\t\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"x\":\"\\u12\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"x\":\"a\u0001b\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"x\":\"ok\""));
    }

    private static AuditJournal fixedJournal(List<AuditRecord> records) {
        return new AuditJournal() {
            @Override public AuditRecord append(AuditEntry entry) { throw new UnsupportedOperationException(); }
            @Override public List<AuditRecord> readRange(AuditScope scope, long from, long to, int limit) { return records; }
            @Override public AuditChainVerification verify(AuditScope scope) { return new AuditChainVerification(true, records.size(), 0,
                    records.isEmpty() ? AuditCanonicalizer.GENESIS_HASH : records.get(records.size() - 1).entryHash()); }
        };
    }
}
