package io.infranexum.integrations;

/** Deterministic conflict behavior for governed connector synchronization. */
public enum ConnectorConflictStrategy {
    REJECT,
    MANUAL,
    PREFER_AUTHORITY
}
