package io.infranexum.core.events;

/** Transactional inbound handler capable of staging domain changes and new events. */
@FunctionalInterface
public interface InboxHandler {
    void handle(EventEnvelope event, EventTransaction transaction) throws Exception;
}
