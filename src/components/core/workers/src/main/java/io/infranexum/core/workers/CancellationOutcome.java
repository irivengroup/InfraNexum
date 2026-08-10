package io.infranexum.core.workers;

/** Explicit result of a cancellation request. */
public enum CancellationOutcome {
    REQUESTED,
    ALREADY_REQUESTED,
    ALREADY_TERMINAL,
    NOT_FOUND
}
