package io.infranexum.core.events;

import java.time.Instant;

/** Transaction-local operations shared by business writes, outbox and inbox. */
public interface EventTransaction {
    /** Stages an event for atomic commit with the caller's business transaction. */
    void append(EventEnvelope event);

    /** Reserves an inbound event unless the same consumer already committed it. */
    InboxDecision beginInbox(InboxKey key);

    /** Marks the accepted inbound event complete in the current transaction. */
    void completeInbox(InboxKey key, EventType eventType, String payloadSha256, Instant receivedAt, Instant completedAt);

    /** Registers a best-effort signal that runs only after a successful commit. */
    void afterCommit(PostCommitAction action);
}
