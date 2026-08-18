package io.infranexum.integrations;

import java.util.Objects;

/** Result of one handler batch, including explicit retry/compensation semantics for partial provider failures. */
public record ConnectorSyncBatchResult(
        Outcome outcome,
        String nextCursor,
        long processedCount,
        long changedCount,
        long rejectedCount,
        boolean completed,
        boolean retryable,
        boolean compensationRequired,
        String failureCode) {
    public enum Outcome { APPLIED, NOOP, FAILED }

    public ConnectorSyncBatchResult {
        Objects.requireNonNull(outcome, "outcome");
        nextCursor = ConnectorSyncCheckpoint.normalizeCursor(nextCursor);
        if (processedCount < 0 || changedCount < 0 || rejectedCount < 0 || changedCount + rejectedCount > processedCount) {
            throw new IllegalArgumentException("invalid synchronization counters");
        }
        if (outcome == Outcome.FAILED) {
            if (completed) throw new IllegalArgumentException("failed batch cannot be completed");
            if (failureCode == null || !failureCode.matches("^[A-Z0-9_:-]{1,64}$")) throw new IllegalArgumentException("failed batch requires stable failureCode");
        } else if (failureCode != null || retryable || compensationRequired) {
            throw new IllegalArgumentException("successful batch cannot carry failure semantics");
        }
    }

    public static ConnectorSyncBatchResult applied(String nextCursor, long processed, long changed, long rejected, boolean completed) {
        return new ConnectorSyncBatchResult(Outcome.APPLIED, nextCursor, processed, changed, rejected, completed, false, false, null);
    }

    public static ConnectorSyncBatchResult failed(String code, boolean retryable, boolean compensationRequired) {
        return new ConnectorSyncBatchResult(Outcome.FAILED, null, 0, 0, 0, false, retryable, compensationRequired, code);
    }
}
