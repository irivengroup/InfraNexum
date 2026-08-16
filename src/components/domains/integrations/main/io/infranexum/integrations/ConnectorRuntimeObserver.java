package io.infranexum.integrations;

import java.time.Duration;

/** Observability port kept out of the domain's metrics implementation. */
public interface ConnectorRuntimeObserver {
    ConnectorRuntimeObserver NOOP = new ConnectorRuntimeObserver() {};
    default void admitted(ConnectorKey key, boolean duplicate) {}
    default void rejected(ConnectorKey key, String reason) {}
    default void processed(ConnectorKey key, Duration latency) {}
    default void retried(ConnectorKey key) {}
    default void deadLettered(ConnectorKey key) {}
    default void replayed(ConnectorKey key) {}
    default void suspended(ConnectorKey key) {}
}
