package io.infranexum.core.events;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Persistence port for atomic business writes, transactional outbox and inbox receipts.
 *
 * <p>Database adapters must bind {@link #execute(TransactionalWork)} to the same
 * physical transaction used by the bounded-context repositories.
 */
public interface TransactionalEventStore {
    <T> TransactionOutcome<T> execute(TransactionalWork<T> work);

    List<OutboxRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration);

    void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt);

    OutboxStatus markFailed(
            DomainIdentifier eventId,
            String workerId,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure);
}
