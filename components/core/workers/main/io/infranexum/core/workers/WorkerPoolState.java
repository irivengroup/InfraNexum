package io.infranexum.core.workers;

/** Lifecycle state of the bounded worker runtime. */
public enum WorkerPoolState {
    NEW,
    RUNNING,
    STOPPING,
    TERMINATED
}
