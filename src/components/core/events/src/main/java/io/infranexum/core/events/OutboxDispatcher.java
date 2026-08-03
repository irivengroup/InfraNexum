package io.infranexum.core.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Claims and publishes one bounded outbox batch using at-least-once delivery. */
public final class OutboxDispatcher {
    private final TransactionalEventStore store;
    private final EventTransport transport;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    public OutboxDispatcher(
            TransactionalEventStore store,
            EventTransport transport,
            RetryPolicy retryPolicy,
            Clock clock,
            String workerId,
            int batchSize,
            Duration leaseDuration) {
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = Objects.requireNonNull(workerId, "workerId").strip();
        if (this.workerId.isEmpty()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    /** Executes one finite iteration; the caller owns scheduling and cancellation. */
    public DispatchReport dispatchOnce() {
        Instant claimedAt = clock.instant();
        List<OutboxRecord> batch = store.claimBatch(workerId, batchSize, claimedAt, leaseDuration);
        int published = 0;
        int retried = 0;
        int deadLettered = 0;
        for (OutboxRecord record : batch) {
            try {
                transport.publish(record.event());
                store.markPublished(record.event().eventId(), workerId, clock.instant());
                published++;
            } catch (Exception failure) {
                OutboxStatus status = store.markFailed(
                        record.event().eventId(), workerId, clock.instant(), retryPolicy, failure);
                if (status == OutboxStatus.DEAD_LETTER) {
                    deadLettered++;
                } else {
                    retried++;
                }
            }
        }
        return new DispatchReport(batch.size(), published, retried, deadLettered);
    }
}
