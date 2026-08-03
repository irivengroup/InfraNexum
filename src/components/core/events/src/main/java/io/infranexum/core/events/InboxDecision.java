package io.infranexum.core.events;

/** Result of attempting to reserve an inbound event in one transaction. */
public enum InboxDecision {
    ACCEPTED,
    DUPLICATE
}
