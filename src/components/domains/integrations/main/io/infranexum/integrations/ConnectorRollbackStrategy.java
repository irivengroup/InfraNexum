package io.infranexum.integrations;

/** Recovery contract required before a connector may perform mutations. */
public enum ConnectorRollbackStrategy {
    NONE_REQUIRED,
    LOCAL_CHECKPOINT,
    REMOTE_COMPENSATION,
    DUAL_COMPENSATION,
    MANUAL
}
