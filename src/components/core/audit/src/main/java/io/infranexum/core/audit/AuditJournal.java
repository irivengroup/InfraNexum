package io.infranexum.core.audit;

import java.util.List;

/** Port owning append-only persistence and bounded ordered reads of audit records. */
public interface AuditJournal {
    AuditRecord append(AuditEntry entry);
    List<AuditRecord> readRange(AuditScope scope, long fromSequenceInclusive, long toSequenceInclusive, int limit);
    AuditChainVerification verify(AuditScope scope);
}
