package io.infranexum.core.events;

/** Observable outcome returned by the deduplicating inbox processor. */
public enum InboxProcessingResult {
    PROCESSED,
    DUPLICATE
}
