package io.infranexum.integrations;

/** Low-cardinality reason for a non-terminal connector synchronization pause. */
public enum ConnectorSyncPauseCause {
    RETRYABLE_FAILURE,
    BATCH_BUDGET_EXHAUSTED
}
