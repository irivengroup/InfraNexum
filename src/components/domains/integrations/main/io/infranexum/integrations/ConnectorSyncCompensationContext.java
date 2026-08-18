package io.infranexum.integrations;

import java.util.Objects;

/** Compensation context. A handler must reverse the run as a whole, not a later unrelated run. */
public record ConnectorSyncCompensationContext(
        ConnectorSyncRun run,
        String initialCursor,
        String currentCursor,
        String failureCode) {
    public ConnectorSyncCompensationContext {
        Objects.requireNonNull(run, "run");
        initialCursor = ConnectorSyncCheckpoint.normalizeCursor(initialCursor);
        currentCursor = ConnectorSyncCheckpoint.normalizeCursor(currentCursor);
        if (failureCode != null && !failureCode.matches("^[A-Z0-9_:-]{1,64}$")) throw new IllegalArgumentException("invalid compensation failureCode");
    }
}
