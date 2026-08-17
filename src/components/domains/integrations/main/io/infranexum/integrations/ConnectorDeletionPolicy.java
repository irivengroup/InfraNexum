package io.infranexum.integrations;

/** Explicit deletion propagation semantics. */
public enum ConnectorDeletionPolicy {
    IGNORE,
    TOMBSTONE,
    MANUAL
}
