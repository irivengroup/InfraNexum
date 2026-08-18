package io.infranexum.integrations;

/** Append-only checkpoint classification; compensation never rewrites prior history. */
public enum ConnectorSyncCheckpointKind {
    PROGRESS,
    COMPENSATION
}
