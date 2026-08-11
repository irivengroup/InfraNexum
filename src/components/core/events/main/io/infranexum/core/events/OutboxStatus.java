package io.infranexum.core.events;

/** Durable lifecycle of a transactional outbox record. */
public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    DEAD_LETTER
}
