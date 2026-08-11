package io.infranexum.core.audit;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe reference implementation preserving append-only semantics in memory. */
public final class InMemoryAppendOnlyAuditJournal implements AuditJournal {
    private static final int MAX_READ = 10_000;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<AuditScope, List<AuditRecord>> records = new HashMap<>();
    private final Set<DomainIdentifier> auditIds = new HashSet<>();

    @Override
    public AuditRecord append(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.lock();
        try {
            if (!auditIds.add(entry.auditId())) throw new IllegalArgumentException("duplicate audit id");
            List<AuditRecord> journal = records.computeIfAbsent(entry.scope(), ignored -> new ArrayList<>());
            long sequence = journal.size() + 1L;
            String previous = journal.isEmpty() ? AuditCanonicalizer.GENESIS_HASH : journal.get(journal.size() - 1).entryHash();
            AuditRecord record = new AuditRecord(sequence, entry, previous, AuditCanonicalizer.hash(sequence, previous, entry));
            journal.add(record);
            return record;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<AuditRecord> readRange(AuditScope scope, long fromSequenceInclusive, long toSequenceInclusive, int limit) {
        Objects.requireNonNull(scope, "scope");
        validateRange(fromSequenceInclusive, toSequenceInclusive, limit);
        lock.lock();
        try {
            return records.getOrDefault(scope, List.of()).stream()
                    .filter(record -> record.sequence() >= fromSequenceInclusive && record.sequence() <= toSequenceInclusive)
                    .limit(limit)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AuditChainVerification verify(AuditScope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            String previous = AuditCanonicalizer.GENESIS_HASH;
            long verified = 0;
            for (AuditRecord record : records.getOrDefault(scope, List.of())) {
                String expected = AuditCanonicalizer.hash(record.sequence(), previous, record.entry());
                if (!record.previousHash().equals(previous) || !record.entryHash().equals(expected)) {
                    return new AuditChainVerification(false, verified, record.sequence(), previous);
                }
                previous = record.entryHash();
                verified++;
            }
            return new AuditChainVerification(true, verified, 0, previous);
        } finally {
            lock.unlock();
        }
    }

    static void validateRange(long fromSequenceInclusive, long toSequenceInclusive, int limit) {
        if (fromSequenceInclusive < 1 || toSequenceInclusive < fromSequenceInclusive) {
            throw new IllegalArgumentException("invalid audit sequence range");
        }
        if (limit < 1 || limit > MAX_READ) throw new IllegalArgumentException("audit read limit must be between 1 and " + MAX_READ);
    }
}
