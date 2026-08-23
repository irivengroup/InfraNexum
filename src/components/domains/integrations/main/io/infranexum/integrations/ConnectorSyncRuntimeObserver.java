package io.infranexum.integrations;

import java.time.Duration;

/**
 * Low-cardinality observability port for durable connector synchronization.
 *
 * <p>Provider payloads, cursor values, failure messages and idempotency keys are deliberately absent from
 * this contract so metrics implementations cannot accidentally turn untrusted or high-cardinality values
 * into labels.</p>
 */
public interface ConnectorSyncRuntimeObserver {
    ConnectorSyncRuntimeObserver NOOP = new ConnectorSyncRuntimeObserver() {};

    default void admitted(ConnectorKey key, ConnectorSyncDirection direction, boolean duplicate) {}

    default void resumed(ConnectorKey key, ConnectorSyncDirection direction) {}

    default void batchApplied(
            ConnectorKey key,
            ConnectorSyncDirection direction,
            long processed,
            long changed,
            long rejected,
            boolean completed) {}

    default void paused(ConnectorKey key, ConnectorSyncDirection direction, ConnectorSyncPauseCause cause) {}

    default void compensationStarted(ConnectorKey key, ConnectorRollbackStrategy rollbackStrategy) {}

    default void terminal(
            ConnectorKey key,
            ConnectorSyncDirection direction,
            ConnectorSyncRunStatus status,
            Duration elapsed) {}
}
