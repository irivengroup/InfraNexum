package io.infranexum.integrations;

import java.util.List;
import java.util.Objects;

/** Bounded source page consumed by a synchronization handler and checkpointed by the engine. */
public record ConnectorOutboundPage(List<ConnectorOutboundRecord> records, String nextCursor, boolean completed) {
    public ConnectorOutboundPage {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.size() > 1_000) throw new IllegalArgumentException("outbound page supports at most 1000 records");
        nextCursor = ConnectorSyncCheckpoint.normalizeCursor(nextCursor);
        if (!completed && records.isEmpty()) {
            throw new IllegalArgumentException("non-completed outbound page must make forward progress");
        }
    }
}
