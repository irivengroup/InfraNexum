package io.infranexum.integrations;

/** Durable lifecycle of one governed connector synchronization run. */
public enum ConnectorSyncRunStatus {
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}
