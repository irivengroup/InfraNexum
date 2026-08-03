package io.infranexum.server.persistence;

/** Explicit persistence backend selected by the Server composition root. */
public enum PersistenceMode {
    /** Non-persistent mode restricted to a local standalone runtime. */
    MEMORY,
    POSTGRESQL,
    ORACLE
}
