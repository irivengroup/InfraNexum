package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Immutable durable synchronization-run projection. Provider credentials and raw payloads are never stored here. */
public record ConnectorSyncRun(
        DomainIdentifier runId,
        ConnectorKey connectorKey,
        String provider,
        ConnectorSyncDirection direction,
        ConnectorRollbackStrategy rollbackStrategy,
        ConnectorSyncRunStatus status,
        String idempotencyKey,
        String requestSha256,
        Set<String> fields,
        boolean propagateDeletions,
        int maxBatches,
        long initialRevision,
        long lastCheckpointRevision,
        String failureCode,
        DomainIdentifier actorId,
        DomainIdentifier correlationId,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        Long compensationCheckpointRevision) {

    public ConnectorSyncRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        if (provider == null || !provider.matches("^[a-z][a-z0-9-]{0,79}$")) throw new IllegalArgumentException("invalid sync provider");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(rollbackStrategy, "rollbackStrategy");
        Objects.requireNonNull(status, "status");
        idempotencyKey = bounded(idempotencyKey, "idempotencyKey", 8, 200, "^[A-Za-z0-9._:-]+$");
        requestSha256 = bounded(requestSha256, "requestSha256", 64, 64, "^[0-9a-f]{64}$");
        fields = Set.copyOf(Objects.requireNonNullElse(fields, Set.<String>of()));
        if (fields.size() > 512) throw new IllegalArgumentException("sync run supports at most 512 fields");
        if (maxBatches < 1 || maxBatches > 100) throw new IllegalArgumentException("maxBatches must be between 1 and 100");
        if (initialRevision < 0 || lastCheckpointRevision < initialRevision) throw new IllegalArgumentException("invalid sync revisions");
        if (failureCode != null) failureCode = bounded(failureCode, "failureCode", 1, 64, "^[A-Z0-9_:-]+$");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (completedAt != null && completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt precedes startedAt");
        if (compensationCheckpointRevision != null && compensationCheckpointRevision < lastCheckpointRevision) {
            throw new IllegalArgumentException("compensation checkpoint precedes run checkpoint");
        }
    }

    public boolean terminal() {
        return status == ConnectorSyncRunStatus.SUCCEEDED || status == ConnectorSyncRunStatus.FAILED
                || status == ConnectorSyncRunStatus.COMPENSATED || status == ConnectorSyncRunStatus.COMPENSATION_FAILED;
    }

    private static String bounded(String value, String field, int min, int max, String regex) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.length() < min || normalized.length() > max || !normalized.matches(regex)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
