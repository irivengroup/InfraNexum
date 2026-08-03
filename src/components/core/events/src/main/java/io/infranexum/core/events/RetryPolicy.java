package io.infranexum.core.events;

import java.time.Duration;

/** Calculates retry delay and dead-letter threshold for failed publications. */
public interface RetryPolicy {
    int maximumAttempts();

    Duration delayAfterFailure(int attempts);
}
