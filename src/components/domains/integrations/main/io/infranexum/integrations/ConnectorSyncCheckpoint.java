package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Append-only connector checkpoint. Raw cursor is internal-only and must never be exposed by public HTTP models. */
public record ConnectorSyncCheckpoint(
        DomainIdentifier checkpointId,
        ConnectorKey connectorKey,
        DomainIdentifier runId,
        long revision,
        ConnectorSyncCheckpointKind kind,
        String cursor,
        String cursorSha256,
        long processedCount,
        long changedCount,
        long rejectedCount,
        Instant createdAt) {
    public ConnectorSyncCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(runId, "runId");
        if (revision < 1) throw new IllegalArgumentException("checkpoint revision must be positive");
        Objects.requireNonNull(kind, "kind");
        cursor = normalizeCursor(cursor);
        if (cursorSha256 == null || !cursorSha256.matches("^[0-9a-f]{64}$")) throw new IllegalArgumentException("invalid cursorSha256");
        if (processedCount < 0 || changedCount < 0 || rejectedCount < 0 || changedCount + rejectedCount > processedCount) {
            throw new IllegalArgumentException("invalid checkpoint counters");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static String normalizeCursor(String value) {
        if (value == null) return null;
        if (value.length() > 2048) throw new IllegalArgumentException("connector cursor exceeds 2048 characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new IllegalArgumentException("connector cursor contains control characters");
        }
        return value;
    }
}
