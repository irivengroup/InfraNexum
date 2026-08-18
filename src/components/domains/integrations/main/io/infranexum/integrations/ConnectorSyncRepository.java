package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable repository contract for idempotent runs, monotonic checkpoints and fenced compensation. */
public interface ConnectorSyncRepository {
    record BeginResult(ConnectorSyncRun run, String cursor, long revision, boolean created) {}
    record Activation(ConnectorSyncRun run, String cursor, long revision) {}
    record CompensationStart(ConnectorSyncRun run, String initialCursor, String currentCursor, long currentRevision) {}

    BeginResult begin(
            DomainIdentifier runId, ConnectorKey connectorKey, String provider,
            ConnectorSyncDirection direction, ConnectorRollbackStrategy rollbackStrategy,
            String idempotencyKey, String requestSha256, Set<String> fields, boolean propagateDeletions, int maxBatches,
            DomainIdentifier actorId, DomainIdentifier correlationId, Instant startedAt);

    Activation activate(DomainIdentifier runId, Instant at);
    ConnectorSyncCheckpoint appendCheckpoint(
            DomainIdentifier checkpointId, DomainIdentifier runId, long expectedRevision,
            ConnectorSyncCheckpointKind kind, String cursor, String cursorSha256,
            long processedCount, long changedCount, long rejectedCount, Instant at);
    ConnectorSyncRun pause(DomainIdentifier runId, String failureCode, Instant at);
    ConnectorSyncRun succeed(DomainIdentifier runId, Instant at);
    ConnectorSyncRun fail(DomainIdentifier runId, String failureCode, Instant at);
    CompensationStart beginCompensation(DomainIdentifier runId, Instant at);
    ConnectorSyncRun finishCompensation(
            DomainIdentifier runId, long expectedRevision, DomainIdentifier checkpointId,
            String restoredCursor, String restoredCursorSha256, Instant at);
    ConnectorSyncRun compensationFailed(DomainIdentifier runId, String failureCode, Instant at);
    Optional<ConnectorSyncRun> findRun(DomainIdentifier runId);
    List<ConnectorSyncRun> listRuns(ConnectorKey connectorKey, int offset, int limit);
    List<ConnectorSyncCheckpoint> listCheckpoints(ConnectorKey connectorKey, int offset, int limit);
}
