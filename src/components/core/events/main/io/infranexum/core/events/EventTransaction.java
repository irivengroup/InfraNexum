package io.infranexum.core.events;

import java.time.Instant;

/** Transaction-local operations shared by business writes, outbox and inbox. */
public interface EventTransaction {
    /** Stages an event for atomic commit with the caller's business transaction. */
    void append(EventEnvelope event);

    /**
     * Reserves an inbound event unless the same consumer already committed it.
     *
     * <p>The reservation is created in the current database transaction. A
     * concurrent delivery therefore waits for the owning transaction and can
     * only continue when that transaction rolls back.
     */
    InboxDecision beginInbox(InboxReservation reservation);

    /** Marks the accepted inbound event complete in the current transaction. */
    void completeInbox(InboxKey key, Instant completedAt);

    /** Registers a best-effort signal that runs only after a successful commit. */
    void afterCommit(PostCommitAction action);
}
