package io.infranexum.core.workers;

/** Declares whether a failed or abandoned execution may be attempted again automatically. */
public enum RetrySafety {
    /** Handler is idempotent for the task idempotency key and may be retried. */
    RETRY_SAFE,
    /** Handler may have non-idempotent side effects and is never retried automatically. */
    AT_MOST_ONCE
}
