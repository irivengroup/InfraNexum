package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** One resumable synchronization batch. Handler implementations must be idempotent for a repeated cursor. */
public record ConnectorSyncBatchContext(
        DomainIdentifier runId,
        ConnectorKey connectorKey,
        ConnectorSyncDirection direction,
        String cursor,
        long currentRevision,
        int batchNumber) {
    public ConnectorSyncBatchContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(direction, "direction");
        cursor = ConnectorSyncCheckpoint.normalizeCursor(cursor);
        if (currentRevision < 0 || batchNumber < 1 || batchNumber > 100) throw new IllegalArgumentException("invalid batch context");
    }
}
