package io.infranexum.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

final class AppendOnlyAuditJournalTest {
    @Test
    void appendsReadsAndVerifiesIndependentScopes() {
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        AuditScope org = AuditScope.organization("org-1");
        AuditRecord first = journal.append(AuditModelTest.entry(1, org, Map.of()));
        AuditRecord second = journal.append(AuditModelTest.entry(2, org, Map.of()));
        AuditRecord platform = journal.append(AuditModelTest.entry(3, AuditScope.platform(), Map.of()));
        assertEquals(1, first.sequence());
        assertEquals(first.entryHash(), second.previousHash());
        assertEquals(AuditCanonicalizer.GENESIS_HASH, platform.previousHash());
        assertEquals(List.of(second), journal.readRange(org, 2, 2, 10));
        assertTrue(journal.verify(org).valid());
        assertEquals(2, journal.verify(org).verifiedRecords());
        assertTrue(journal.verify(AuditScope.organization("missing")).valid());
        assertThrows(IllegalArgumentException.class, () -> journal.append(AuditModelTest.entry(1, org, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> journal.readRange(org, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> journal.readRange(org, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> journal.readRange(org, 1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> journal.readRange(org, 1, 2, 10_001));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectsTamperedHashChain() throws Exception {
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        AuditScope scope = AuditScope.organization("org-1");
        journal.append(AuditModelTest.entry(4, scope, Map.of()));
        journal.append(AuditModelTest.entry(5, scope, Map.of()));
        Field field = InMemoryAppendOnlyAuditJournal.class.getDeclaredField("records");
        field.setAccessible(true);
        Map<AuditScope, List<AuditRecord>> records = (Map<AuditScope, List<AuditRecord>>) field.get(journal);
        List<AuditRecord> mutable = records.get(scope);
        AuditRecord original = mutable.get(1);
        mutable.set(1, new AuditRecord(original.sequence(), original.entry(), AuditCanonicalizer.GENESIS_HASH, original.entryHash()));
        AuditChainVerification verification = journal.verify(scope);
        assertFalse(verification.valid());
        assertEquals(2, verification.failingSequence());
        assertEquals(1, verification.verifiedRecords());
    }

    @Test
    void concurrentAppendProducesUniqueContiguousSequence() throws Exception {
        InMemoryAppendOnlyAuditJournal journal = new InMemoryAppendOnlyAuditJournal();
        AuditScope scope = AuditScope.organization("org-concurrent");
        int count = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= count; i++) {
                final int sequence = i + 100;
                futures.add(executor.submit(() -> {
                    start.await();
                    journal.append(AuditModelTest.entry(sequence, scope, Map.of("worker", Integer.toString(sequence % 8))));
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
        List<AuditRecord> records = journal.readRange(scope, 1, count, count);
        assertEquals(count, records.size());
        for (int i = 0; i < count; i++) assertEquals(i + 1L, records.get(i).sequence());
        assertTrue(journal.verify(scope).valid());
    }
}
