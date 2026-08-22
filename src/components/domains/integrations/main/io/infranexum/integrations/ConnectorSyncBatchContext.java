package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;
import java.util.Set;

/** One resumable synchronization batch. Handler implementations must be idempotent for a repeated cursor. */
public record ConnectorSyncBatchContext(
        DomainIdentifier runId,
        ConnectorKey connectorKey,
        ConnectorSyncDirection direction,
        String cursor,
        long currentRevision,
        int batchNumber,
        Set<String> fields,
        boolean propagateDeletions) {

    /** Compatibility constructor retained for provider-neutral smoke fixtures predating field propagation. */
    public ConnectorSyncBatchContext(
            DomainIdentifier runId,
            ConnectorKey connectorKey,
            ConnectorSyncDirection direction,
            String cursor,
            long currentRevision,
            int batchNumber) {
        this(runId, connectorKey, direction, cursor, currentRevision, batchNumber, Set.<String>of(), false);
    }

    public ConnectorSyncBatchContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(direction, "direction");
        cursor = ConnectorSyncCheckpoint.normalizeCursor(cursor);
        if (currentRevision < 0 || batchNumber < 1 || batchNumber > 100) {
            throw new IllegalArgumentException("invalid batch context");
        }
        fields = Set.copyOf(Objects.requireNonNullElse(fields, Set.<String>of()));
        if (fields.size() > 512) throw new IllegalArgumentException("batch context supports at most 512 fields");
        for (String field : fields) new ConnectorFieldAuthority(field, ConnectorDataAuthority.MANUAL);
    }
}
