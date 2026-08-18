package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Durable connector synchronization engine.
 *
 * <p>The engine never assumes exactly-once delivery: handlers must be idempotent for a repeated cursor,
 * every successful batch is checkpointed, and partial mutation failures are either compensated explicitly
 * or surfaced as a non-success terminal state.</p>
 */
public final class ConnectorSyncEngine {
    private final ConnectorGovernanceRegistry governance;
    private final ConnectorGovernancePlanner planner;
    private final ConnectorSyncHandlerRegistry handlers;
    private final ConnectorSyncRepository repository;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ConnectorSyncEngine(
            ConnectorGovernanceRegistry governance, ConnectorGovernancePlanner planner,
            ConnectorSyncHandlerRegistry handlers, ConnectorSyncRepository repository,
            UuidV7Generator ids, Clock clock) {
        this.governance = Objects.requireNonNull(governance, "governance");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConnectorSyncRun execute(
            ConnectorKey connectorKey, ConnectorSyncExecutionRequest request, String idempotencyKey,
            DomainIdentifier actorId, DomainIdentifier correlationId) {
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(request, "request");
        ConnectorGovernancePolicy policy = governedMutation(connectorKey, request);
        ConnectorSyncHandler handler = handlers.require(connectorKey);
        Instant now = clock.instant();
        String hash = requestHash(request);
        ConnectorSyncRepository.BeginResult begin = repository.begin(
                ids.next(), connectorKey, policy.provider(), request.direction(), policy.rollbackStrategy(),
                idempotencyKey, hash, request.fields(), request.propagateDeletions(), request.maxBatches(),
                actorId, correlationId, now);
        if (!begin.created()) return begin.run();
        return process(begin.run(), begin.cursor(), begin.revision(), handler);
    }

    public ConnectorSyncRun resume(DomainIdentifier runId) {
        ConnectorSyncRepository.Activation activation = repository.activate(Objects.requireNonNull(runId, "runId"), clock.instant());
        ConnectorSyncRun run = activation.run();
        ConnectorGovernancePolicy policy = governance.require(run.connectorKey());
        if (policy.direction() != run.direction() || policy.rollbackStrategy() != run.rollbackStrategy()) {
            return repository.fail(run.runId(), "POLICY_CHANGED", clock.instant());
        }
        ConnectorSyncHandler handler = handlers.require(run.connectorKey());
        return process(run, activation.cursor(), activation.revision(), handler);
    }

    public ConnectorSyncRun compensate(DomainIdentifier runId) {
        ConnectorSyncRepository.CompensationStart start = repository.beginCompensation(Objects.requireNonNull(runId, "runId"), clock.instant());
        return compensateStarted(start, handlers.require(start.run().connectorKey()), "OPERATOR_REQUEST");
    }

    private ConnectorGovernancePolicy governedMutation(ConnectorKey key, ConnectorSyncExecutionRequest request) {
        ConnectorGovernancePolicy policy = governance.require(key);
        ConnectorSyncPlan plan = planner.plan(policy, request.asPlanRequest());
        if (plan.decision() != ConnectorSyncPlan.Decision.ALLOW) {
            throw new ConnectorSyncPolicyDeniedException(String.join("; ", plan.reasons()));
        }
        if (!request.direction().mutating()) {
            throw new ConnectorSyncPolicyDeniedException("federated-read connectors are queried directly and are not executable synchronization jobs");
        }
        return policy;
    }

    private ConnectorSyncRun process(ConnectorSyncRun run, String cursor, long revision, ConnectorSyncHandler handler) {
        String currentCursor = cursor;
        long currentRevision = revision;
        for (int batch = 1; batch <= run.maxBatches(); batch++) {
            ConnectorSyncBatchResult result = Objects.requireNonNull(
                    handler.synchronize(new ConnectorSyncBatchContext(
                            run.runId(), run.connectorKey(), run.direction(), currentCursor, currentRevision, batch)),
                    "connector sync handler result");
            if (result.outcome() == ConnectorSyncBatchResult.Outcome.FAILED) {
                if (result.compensationRequired()) {
                    if (!automaticCompensation(run.rollbackStrategy())) {
                        return repository.fail(run.runId(), "MANUAL_COMPENSATION_REQUIRED", clock.instant());
                    }
                    ConnectorSyncRepository.CompensationStart start = repository.beginCompensation(run.runId(), clock.instant());
                    return compensateStarted(start, handler, result.failureCode());
                }
                return result.retryable()
                        ? repository.pause(run.runId(), result.failureCode(), clock.instant())
                        : repository.fail(run.runId(), result.failureCode(), clock.instant());
            }
            String nextCursor = result.nextCursor() == null ? currentCursor : result.nextCursor();
            ConnectorSyncCheckpoint checkpoint = repository.appendCheckpoint(
                    ids.next(), run.runId(), currentRevision, ConnectorSyncCheckpointKind.PROGRESS,
                    nextCursor, sha256(nextCursor), result.processedCount(), result.changedCount(), result.rejectedCount(), clock.instant());
            currentCursor = nextCursor;
            currentRevision = checkpoint.revision();
            if (result.completed()) return repository.succeed(run.runId(), clock.instant());
        }
        return repository.pause(run.runId(), "BATCH_BUDGET_EXHAUSTED", clock.instant());
    }

    private ConnectorSyncRun compensateStarted(
            ConnectorSyncRepository.CompensationStart start, ConnectorSyncHandler handler, String failureCode) {
        ConnectorSyncRun run = start.run();
        if (!automaticCompensation(run.rollbackStrategy())) {
            return repository.fail(run.runId(), "MANUAL_COMPENSATION_REQUIRED", clock.instant());
        }
        ConnectorSyncCompensationResult result = Objects.requireNonNull(
                handler.compensate(new ConnectorSyncCompensationContext(run, start.initialCursor(), start.currentCursor(), failureCode)),
                "connector compensation result");
        if (!result.success()) return repository.compensationFailed(run.runId(), result.failureCode(), clock.instant());
        return repository.finishCompensation(
                run.runId(), start.currentRevision(), ids.next(), start.initialCursor(), sha256(start.initialCursor()), clock.instant());
    }

    private static boolean automaticCompensation(ConnectorRollbackStrategy strategy) {
        return strategy == ConnectorRollbackStrategy.LOCAL_CHECKPOINT
                || strategy == ConnectorRollbackStrategy.REMOTE_COMPENSATION
                || strategy == ConnectorRollbackStrategy.DUAL_COMPENSATION;
    }

    private static String requestHash(ConnectorSyncExecutionRequest request) {
        List<String> fields = new ArrayList<>(request.fields());
        fields.sort(Comparator.naturalOrder());
        String canonical = request.direction().name() + "\n" + String.join("\n", fields) + "\n"
                + request.propagateDeletions() + "\n" + request.maxBatches();
        return sha256(canonical);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
