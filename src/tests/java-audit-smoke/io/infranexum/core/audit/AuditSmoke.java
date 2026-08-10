package io.infranexum.core.audit;

import io.infranexum.core.contracts.DomainIdentifier;
import java.io.ByteArrayInputStream;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/** Dependency-free executable contract smoke for Core Audit. */
public final class AuditSmoke {
    private AuditSmoke() {}

    public static void main(String[] args) throws Exception {
        AuditScope scope = AuditScope.organization("org-smoke");
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        journal.append(entry(1, scope));
        journal.append(entry(2, scope));
        require(journal.verify(scope).valid(), "audit chain is invalid");
        require(journal.readRange(scope, 1, 2, 2).get(1).sequence() == 2, "audit ordering is invalid");

        int concurrent = 32;
        AuditScope concurrentScope = AuditScope.organization("org-concurrent");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < concurrent; i++) {
                final int value = 100 + i;
                executor.submit(() -> { start.await(); journal.append(entry(value, concurrentScope)); return null; });
            }
            start.countDown();
            executor.shutdown();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "concurrent audit append did not terminate");
        } finally {
            executor.shutdownNow();
        }
        var records = journal.readRange(concurrentScope, 1, concurrent, concurrent);
        Set<Long> sequences = new HashSet<>();
        for (AuditRecord record : records) sequences.add(record.sequence());
        require(sequences.size() == concurrent && journal.verify(concurrentScope).valid(), "concurrent audit chain is inconsistent");

        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AuditExportService exporter = new AuditExportService(journal);
        SignedAuditExport first = exporter.export(scope, 1, 2, 2, "smoke-key", keyPair.getPrivate());
        SignedAuditExport second = exporter.export(scope, 1, 2, 2, "smoke-key", keyPair.getPrivate());
        require(java.util.Arrays.equals(first.archive(), second.archive()), "audit export is not deterministic");
        require(new AuditExportVerifier().verify(first, keyPair.getPublic()), "audit export signature is invalid");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.archive()))) {
            require("audit.jsonl".equals(zip.getNextEntry().getName()), "audit export payload is missing");
            require("manifest.properties".equals(zip.getNextEntry().getName()), "audit export manifest is missing");
            require("signature.ed25519.b64".equals(zip.getNextEntry().getName()), "audit export signature is missing");
        }
        expect(IllegalArgumentException.class, () -> entry(999, scope, Map.of("access_token", "forbidden")));
        System.out.println("java-audit-smoke: PASS");
    }

    private static AuditEntry entry(int value, AuditScope scope) {
        return entry(value, scope, Map.of("source", "smoke"));
    }

    private static AuditEntry entry(int value, AuditScope scope, Map<String, String> metadata) {
        String suffix = "%012d".formatted(value);
        return new AuditEntry(
                DomainIdentifier.parse("018bcfe5-6800-7000-8000-" + suffix),
                scope,
                "user-1",
                "USER",
                "platform.configuration.change",
                "CONFIGURATION",
                "runtime",
                "ALLOW",
                Instant.parse("2026-08-10T08:00:00Z").plusMillis(value),
                DomainIdentifier.parse("018bcfe5-6800-7001-8000-000000000001"),
                "SUCCESS",
                "api/server",
                "approved change",
                "192.0.2.10",
                "audit-smoke",
                metadata,
                "INTERNAL");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) throws Exception {
        try { action.run(); }
        catch (Throwable error) { if (type.isInstance(error)) return; throw new AssertionError(error); }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
