package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.workers.CancellationOutcome;
import io.infranexum.core.workers.IdempotencyConflictException;
import io.infranexum.core.workers.RetrySafety;
import io.infranexum.core.workers.TaskCheckpoint;
import io.infranexum.core.workers.TaskId;
import io.infranexum.core.workers.TaskLeaseLostException;
import io.infranexum.core.workers.TaskStatus;
import io.infranexum.core.workers.TaskSubmission;
import io.infranexum.core.workers.TaskType;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Dependency-free JDBC workers contract smoke using a strict scripted JDBC driver. */
public final class JdbcTaskStoreSmoke {
    private static final Instant NOW = Instant.parse("2026-08-10T16:00:00Z");
    private static final TaskId TASK_ID = new TaskId(new DomainIdentifier(
            UUID.fromString("018bcfe5-6800-7000-8000-000000000901")));

    private JdbcTaskStoreSmoke() {}

    public static void main(String[] args) {
        provesSubmissionReplayAndConflict();
        provesSubmissionRaceAndPersistenceFailures();
        provesClaimCheckpointRetryAndCancellation();
        provesClaimRecoveryVariantsAndOracleDialect();
        provesCompletionAndFailureTransitions();
        provesCancellationOutcomesAndFind();
        provesLeaseFencingAndRecovery();
        provesCheckpointFailureDiagnostics();
        provesTransactionAndDataGuards();
        provesConfigurationGuards();
        System.out.println("java-jdbc-workers-smoke: PASS");
    }

    static void provesSubmissionReplayAndConflict() {
        ScriptedDataSource created = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.update("/*inx:task-insert*/", 1),
                Step.batch("/*inx:task-parameter-insert*/", 1));
        JdbcTaskStore store = new JdbcTaskStore(created, JdbcDatabaseDialect.POSTGRESQL);
        var result = store.submit(TASK_ID, submission("alpha"), RetrySafety.RETRY_SAFE, NOW);
        require(result.created() && result.taskId().equals(TASK_ID), "new task was not created");
        created.assertExhausted();

        Map<String, Object> replayRow = row(
                "task_id", TASK_ID.value().value(),
                "task_type", "core.test",
                "idempotency_key", "idem-1",
                "retry_safety", "RETRY_SAFE",
                "requested_not_before", NOW);
        Map<String, Object> parameterRow = row(
                "task_id", TASK_ID.value().value(),
                "parameter_key", "payload",
                "parameter_value", "alpha");
        ScriptedDataSource replay = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/", replayRow),
                Step.query("/*inx:task-parameters*/", parameterRow));
        var replayed = new JdbcTaskStore(replay, JdbcDatabaseDialect.POSTGRESQL)
                .submit(newTaskId(902), submission("alpha"), RetrySafety.RETRY_SAFE, NOW.plusSeconds(1));
        require(!replayed.created() && replayed.taskId().equals(TASK_ID), "idempotent replay changed task id");
        replay.assertExhausted();

        ScriptedDataSource conflict = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/", replayRow),
                Step.query("/*inx:task-parameters*/", parameterRow));
        expect(IdempotencyConflictException.class, () -> new JdbcTaskStore(
                conflict, JdbcDatabaseDialect.POSTGRESQL).submit(
                        newTaskId(903), submission("different"), RetrySafety.RETRY_SAFE, NOW));
        conflict.assertExhausted();
    }

    static void provesClaimCheckpointRetryAndCancellation() {
        Map<String, Object> due = row("task_id", TASK_ID.value().value());
        Map<String, Object> running = taskRow("RUNNING", "worker-a", 1L, "N", null);
        Map<String, Object> parameters = row(
                "task_id", TASK_ID.value().value(),
                "parameter_key", "payload",
                "parameter_value", "alpha");
        ScriptedDataSource claim = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/"),
                Step.query("/*inx:task-due*/", due),
                Step.batch("/*inx:task-claim*/", 1),
                Step.query("/*inx:task-read*/", running),
                Step.query("/*inx:task-parameters*/", parameters));
        JdbcTaskStore store = new JdbcTaskStore(claim, JdbcDatabaseDialect.POSTGRESQL);
        var policy = retryPolicy();
        var claimed = store.claimBatch("worker-a", 1, NOW, Duration.ofSeconds(30), policy);
        require(claimed.size() == 1, "due task was not claimed");
        require(claimed.getFirst().status() == TaskStatus.RUNNING, "claim did not return RUNNING state");
        require(claimed.getFirst().parameters().get("payload").equals("alpha"), "parameters were not restored");
        claim.assertExhausted();

        ScriptedDataSource checkpoint = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 1),
                Step.query("/*inx:task-checkpoint-read*/", row(
                        "checkpoint_sequence", 1L,
                        "checkpoint_token", "resume-1",
                        "checkpoint_at", NOW.plusSeconds(1))));
        var saved = new JdbcTaskStore(checkpoint, JdbcDatabaseDialect.POSTGRESQL)
                .saveCheckpoint(TASK_ID, "worker-a", 1, "resume-1", NOW.plusSeconds(1), Duration.ofSeconds(30));
        require(saved.sequence() == 1 && saved.token().equals("resume-1"), "checkpoint was not persisted");
        checkpoint.assertExhausted();

        ScriptedDataSource failure = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "N", 1)),
                Step.update("/*inx:task-fail*/", 1));
        TaskStatus status = new JdbcTaskStore(failure, JdbcDatabaseDialect.POSTGRESQL)
                .markFailed(TASK_ID, "worker-a", 1, NOW.plusSeconds(2), policy, new SQLException("offline"));
        require(status == TaskStatus.PENDING, "retry-safe failure did not become pending");
        failure.assertExhausted();

        ScriptedDataSource cancellation = new ScriptedDataSource(
                Step.query("/*inx:task-cancel-state*/", row("status", "PENDING", "cancellation_requested", "N")),
                Step.update("/*inx:task-cancel*/", 1));
        CancellationOutcome outcome = new JdbcTaskStore(cancellation, JdbcDatabaseDialect.POSTGRESQL)
                .requestCancellation(TASK_ID, NOW.plusSeconds(3));
        require(outcome == CancellationOutcome.REQUESTED, "pending cancellation was not accepted");
        cancellation.assertExhausted();
    }

    static void provesLeaseFencingAndRecovery() {
        ScriptedDataSource fenced = new ScriptedDataSource(
                Step.update("/*inx:task-renew*/", 0),
                Step.query("/*inx:task-lease-state*/", leaseState("worker-b", 2L, "N", 2)));
        expect(TaskLeaseLostException.class, () -> new JdbcTaskStore(
                fenced, JdbcDatabaseDialect.POSTGRESQL).renewLease(
                        TASK_ID, "worker-a", 1, NOW, Duration.ofSeconds(30)));
        fenced.assertExhausted();

        Map<String, Object> expired = row(
                "task_id", TASK_ID.value().value(),
                "retry_safety", "AT_MOST_ONCE",
                "attempts", 1,
                "available_at", NOW.minusSeconds(20),
                "lease_version", 1L,
                "cancellation_requested", "N");
        ScriptedDataSource recovery = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", expired),
                Step.update("/*inx:task-recover*/", 1),
                Step.query("/*inx:task-due*/"));
        var claimed = new JdbcTaskStore(recovery, JdbcDatabaseDialect.POSTGRESQL)
                .claimBatch("worker-b", 1, NOW, Duration.ofSeconds(30), retryPolicy());
        require(claimed.isEmpty(), "at-most-once expired task was incorrectly reclaimed");
        recovery.assertExhausted();
    }

    static void provesConfigurationGuards() {
        expect(NullPointerException.class, () -> new JdbcTaskStore(null, JdbcDatabaseDialect.POSTGRESQL));
        expect(NullPointerException.class, () -> new JdbcTaskStore(new ScriptedDataSource(), null));
        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                new ScriptedDataSource(), JdbcDatabaseDialect.POSTGRESQL, Connection.TRANSACTION_NONE));
        JdbcTaskStore store = new JdbcTaskStore(new ScriptedDataSource(), JdbcDatabaseDialect.POSTGRESQL);
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "worker", 0, NOW, Duration.ofSeconds(1), retryPolicy()));
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "worker", 1_001, NOW, Duration.ofSeconds(1), retryPolicy()));
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "   ", 1, NOW, Duration.ofSeconds(1), retryPolicy()));
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "w".repeat(161), 1, NOW, Duration.ofSeconds(1), retryPolicy()));
        expect(NullPointerException.class, () -> store.claimBatch(
                "worker", 1, null, Duration.ofSeconds(1), retryPolicy()));
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "worker", 1, NOW, Duration.ZERO, retryPolicy()));
        expect(IllegalArgumentException.class, () -> store.claimBatch(
                "worker", 1, NOW, Duration.ofSeconds(-1), retryPolicy()));
        expect(NullPointerException.class, () -> store.claimBatch(
                "worker", 1, NOW, null, retryPolicy()));
        expect(NullPointerException.class, () -> store.claimBatch(
                "worker", 1, NOW, Duration.ofSeconds(1), null));
    }

    static void provesSubmissionRaceAndPersistenceFailures() {
        Map<String, Object> replayRow = row(
                "task_id", TASK_ID.value().value(),
                "task_type", "core.test",
                "idempotency_key", "idem-1",
                "retry_safety", "RETRY_SAFE",
                "requested_not_before", NOW);
        Map<String, Object> parameterRow = row(
                "task_id", TASK_ID.value().value(),
                "parameter_key", "payload",
                "parameter_value", "alpha");
        SQLException unique = new SQLException("duplicate", "23505");
        ScriptedDataSource raced = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.updateFailure("/*inx:task-insert*/", unique),
                Step.query("/*inx:task-idempotency*/", replayRow),
                Step.query("/*inx:task-parameters*/", parameterRow));
        var result = new JdbcTaskStore(raced, JdbcDatabaseDialect.POSTGRESQL)
                .submit(newTaskId(904), submission("alpha"), RetrySafety.RETRY_SAFE, NOW);
        require(!result.created() && result.taskId().equals(TASK_ID), "unique race was not replayed safely");
        raced.assertExhausted();

        ScriptedDataSource duplicateId = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.updateFailure("/*inx:task-insert*/", new SQLException("duplicate id", "23505")),
                Step.query("/*inx:task-idempotency*/"),
                Step.query("/*inx:task-exists*/", row("task_id", TASK_ID.value().value())));
        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                duplicateId, JdbcDatabaseDialect.POSTGRESQL).submit(
                        TASK_ID, submission("alpha"), RetrySafety.RETRY_SAFE, NOW));
        duplicateId.assertExhausted();

        ScriptedDataSource sqlFailure = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.updateFailure("/*inx:task-insert*/", new SQLException("serialization", "40001")));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                sqlFailure, JdbcDatabaseDialect.POSTGRESQL).submit(
                        newTaskId(905), submission("alpha"), RetrySafety.RETRY_SAFE, NOW));
        sqlFailure.assertExhausted();

        ScriptedDataSource badParameterBatch = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.update("/*inx:task-insert*/", 1),
                Step.batch("/*inx:task-parameter-insert*/", 0));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                badParameterBatch, JdbcDatabaseDialect.POSTGRESQL).submit(
                        newTaskId(906), submission("alpha"), RetrySafety.RETRY_SAFE, NOW));
        badParameterBatch.assertExhausted();

        TaskSubmission noParameters = new TaskSubmission(new TaskType("core.empty"), "idem-empty", Map.of(), NOW);
        ScriptedDataSource emptyParameters = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.update("/*inx:task-insert*/", 1));
        var emptyResult = new JdbcTaskStore(emptyParameters, JdbcDatabaseDialect.POSTGRESQL)
                .submit(newTaskId(907), noParameters, RetrySafety.AT_MOST_ONCE, NOW.minusSeconds(10));
        require(emptyResult.created(), "parameterless task was not created");
        emptyParameters.assertExhausted();
    }

    static void provesClaimRecoveryVariantsAndOracleDialect() {
        Map<String, Object> retryExpired = expiredLease("RETRY_SAFE", 1, 11L, "N");
        ScriptedDataSource retryRecovery = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", retryExpired),
                Step.update("/*inx:task-recover*/", 1),
                Step.query("/*inx:task-due*/"));
        require(new JdbcTaskStore(retryRecovery, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker-r", 2, NOW, Duration.ofSeconds(30), retryPolicy()).isEmpty(),
                "retry-safe expired task should be released, not claimed in the same locked scan");
        retryRecovery.assertExhausted();

        ScriptedDataSource cancelledRecovery = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", expiredLease("RETRY_SAFE", 1, 12L, "Y")),
                Step.update("/*inx:task-recover*/", 1),
                Step.query("/*inx:task-due*/"));
        require(new JdbcTaskStore(cancelledRecovery, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker-r", 2, NOW, Duration.ofSeconds(30), retryPolicy()).isEmpty(),
                "cancelled expired task was reclaimed");
        cancelledRecovery.assertExhausted();

        ScriptedDataSource exhaustedRecovery = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", expiredLease("RETRY_SAFE", 3, 13L, "N")),
                Step.update("/*inx:task-recover*/", 1),
                Step.query("/*inx:task-due*/"));
        require(new JdbcTaskStore(exhaustedRecovery, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker-r", 2, NOW, Duration.ofSeconds(30), retryPolicy()).isEmpty(),
                "max-attempt expired task was reclaimed");
        exhaustedRecovery.assertExhausted();

        ScriptedDataSource recoveryRace = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", expiredLease("RETRY_SAFE", 1, 14L, "N")),
                Step.update("/*inx:task-recover*/", 0),
                Step.query("/*inx:task-due*/"));
        require(new JdbcTaskStore(recoveryRace, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker-r", 2, NOW, Duration.ofSeconds(30), retryPolicy()).isEmpty(),
                "optimistic recovery race should be benign");
        require(recoveryRace.observedSql().stream()
                        .filter(sql -> sql.contains("/*inx:task-expired*/"))
                        .allMatch(sql -> sql.contains("LIMIT ?") && !sql.contains("FOR UPDATE")),
                "PostgreSQL expiry recovery must be SQL-bounded and non-locking");
        recoveryRace.assertExhausted();

        ScriptedDataSource corruptedRecovery = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/", expiredLease("RETRY_SAFE", 1, 15L, "N")),
                Step.update("/*inx:task-recover*/", 2));
        expect(IllegalStateException.class, () -> new JdbcTaskStore(
                corruptedRecovery, JdbcDatabaseDialect.POSTGRESQL).claimBatch(
                        "worker-r", 2, NOW, Duration.ofSeconds(30), retryPolicy()));
        corruptedRecovery.assertExhausted();

        Map<String, Object> oracleDue = row("task_id", TASK_ID.toString());
        Map<String, Object> oracleRunning = taskRowWithId(
                TASK_ID.toString(), "RUNNING", "worker-o", 3L, "N", null, null);
        ScriptedDataSource oracle = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/"),
                Step.query("/*inx:task-due*/", oracleDue),
                Step.batch("/*inx:task-claim*/", 1),
                Step.query("/*inx:task-read*/", oracleRunning),
                Step.query("/*inx:task-parameters*/"));
        var oracleClaim = new JdbcTaskStore(oracle, JdbcDatabaseDialect.ORACLE)
                .claimBatch("worker-o", 1, NOW, Duration.ofSeconds(15), retryPolicy());
        require(oracleClaim.size() == 1 && oracleClaim.getFirst().leaseVersion() == 3L,
                "Oracle claim path did not restore the leased task");
        require(oracle.observedSql().stream().anyMatch(sql -> sql.contains("INFRANEXUM_CORE_WORKER_TASK")),
                "Oracle table naming was not selected");
        require(oracle.observedSql().stream()
                        .filter(sql -> sql.contains("/*inx:task-due*/"))
                        .noneMatch(sql -> sql.contains("LIMIT")),
                "Oracle claim SQL must not use LIMIT");
        require(oracle.observedSql().stream()
                        .filter(sql -> sql.contains("/*inx:task-expired*/"))
                        .allMatch(sql -> sql.contains("ROWNUM <= ?") && !sql.contains("FOR UPDATE")),
                "Oracle expiry recovery must be ROWNUM-bounded and non-locking");
        oracle.assertExhausted();

        TaskSubmission oracleEmptyValue = new TaskSubmission(
                new TaskType("core.oracle"), "oracle-empty", Map.of("payload", ""), NOW.minusSeconds(5));
        ScriptedDataSource oracleSubmit = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.update("/*inx:task-insert*/", 1),
                Step.batch("/*inx:task-parameter-insert*/", 1));
        var oracleCreated = new JdbcTaskStore(oracleSubmit, JdbcDatabaseDialect.ORACLE)
                .submit(newTaskId(909), oracleEmptyValue, RetrySafety.RETRY_SAFE, NOW);
        require(oracleCreated.created(), "Oracle CLOB parameter submission failed");
        oracleSubmit.assertExhausted();

        ScriptedDataSource oracleCheckpoint = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 1),
                Step.query("/*inx:task-checkpoint-read*/", row(
                        "checkpoint_sequence", 4L,
                        "checkpoint_token", "oracle-resume",
                        "checkpoint_at", NOW)));
        TaskCheckpoint oracleCheckpointResult = new JdbcTaskStore(
                        oracleCheckpoint, JdbcDatabaseDialect.ORACLE)
                .saveCheckpoint(
                        TASK_ID, "worker-o", 3, "oracle-resume", NOW, Duration.ofSeconds(30));
        require(oracleCheckpointResult.sequence() == 4L
                        && oracleCheckpointResult.token().equals("oracle-resume"),
                "Oracle CLOB checkpoint round-trip failed");
        oracleCheckpoint.assertExhausted();

        ScriptedDataSource changedClaim = new ScriptedDataSource(
                Step.query("/*inx:task-expired*/"),
                Step.query("/*inx:task-due*/", row("task_id", TASK_ID.value().value())),
                Step.batch("/*inx:task-claim*/", 0));
        expect(IllegalStateException.class, () -> new JdbcTaskStore(
                changedClaim, JdbcDatabaseDialect.POSTGRESQL).claimBatch(
                        "worker-a", 1, NOW, Duration.ofSeconds(30), retryPolicy()));
        changedClaim.assertExhausted();
    }

    static void provesCompletionAndFailureTransitions() {
        ScriptedDataSource renew = new ScriptedDataSource(Step.update("/*inx:task-renew*/", 1));
        new JdbcTaskStore(renew, JdbcDatabaseDialect.POSTGRESQL)
                .renewLease(TASK_ID, " worker-a ", 1, NOW, Duration.ofSeconds(30));
        renew.assertExhausted();

        ScriptedDataSource success = new ScriptedDataSource(Step.update("/*inx:task-complete*/", 1));
        new JdbcTaskStore(success, JdbcDatabaseDialect.POSTGRESQL)
                .markSucceeded(TASK_ID, "worker-a", 1, NOW.plusSeconds(1));
        success.assertExhausted();

        ScriptedDataSource cancelled = new ScriptedDataSource(Step.update("/*inx:task-complete*/", 1));
        new JdbcTaskStore(cancelled, JdbcDatabaseDialect.POSTGRESQL)
                .markCancelled(TASK_ID, "worker-a", 1, NOW.plusSeconds(1));
        cancelled.assertExhausted();

        ScriptedDataSource cancellationWins = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "Y", 1)),
                Step.update("/*inx:task-fail*/", 1));
        require(new JdbcTaskStore(cancellationWins, JdbcDatabaseDialect.POSTGRESQL)
                        .markFailed(TASK_ID, "worker-a", 1, NOW, retryPolicy(), new RuntimeException())
                        == TaskStatus.CANCELLED,
                "cancellation did not win over retry");
        cancellationWins.assertExhausted();

        ScriptedDataSource retryExhausted = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "N", 3)),
                Step.update("/*inx:task-fail*/", 1));
        require(new JdbcTaskStore(retryExhausted, JdbcDatabaseDialect.POSTGRESQL)
                        .markFailed(TASK_ID, "worker-a", 1, NOW, retryPolicy(), new SQLException("boom"))
                        == TaskStatus.FAILED,
                "max-attempt retry-safe task did not fail terminally");
        retryExhausted.assertExhausted();

        ScriptedDataSource atMostOnce = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState(
                        "worker-a", 1L, "N", 1, "AT_MOST_ONCE")),
                Step.update("/*inx:task-fail*/", 1));
        require(new JdbcTaskStore(atMostOnce, JdbcDatabaseDialect.POSTGRESQL)
                        .markFailed(TASK_ID, "worker-a", 1, NOW, retryPolicy(), new SQLException("unknown"))
                        == TaskStatus.FAILED,
                "at-most-once task was retried after explicit failure");
        atMostOnce.assertExhausted();

        ScriptedDataSource terminalFailure = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "N", 1)),
                Step.update("/*inx:task-fail*/", 1));
        new JdbcTaskStore(terminalFailure, JdbcDatabaseDialect.POSTGRESQL).markTerminalFailure(
                TASK_ID, "worker-a", 1, NOW, new IllegalStateException("fatal"));
        terminalFailure.assertExhausted();

        ScriptedDataSource terminalCancelled = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "Y", 1)),
                Step.update("/*inx:task-fail*/", 1));
        new JdbcTaskStore(terminalCancelled, JdbcDatabaseDialect.POSTGRESQL).markTerminalFailure(
                TASK_ID, "worker-a", 1, NOW, new IllegalStateException("ignored after cancel"));
        terminalCancelled.assertExhausted();

        String longFailure = "x".repeat(2_000);
        ScriptedDataSource longFailureSource = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "N", 3)),
                Step.update("/*inx:task-fail*/", 1));
        new JdbcTaskStore(longFailureSource, JdbcDatabaseDialect.POSTGRESQL).markFailed(
                TASK_ID, "worker-a", 1, NOW, retryPolicy(), new RuntimeException(longFailure));
        longFailureSource.assertExhausted();
    }

    static void provesCancellationOutcomesAndFind() {
        ScriptedDataSource notFound = new ScriptedDataSource(Step.query("/*inx:task-cancel-state*/"));
        require(new JdbcTaskStore(notFound, JdbcDatabaseDialect.POSTGRESQL)
                        .requestCancellation(TASK_ID, NOW) == CancellationOutcome.NOT_FOUND,
                "unknown cancellation did not return NOT_FOUND");
        notFound.assertExhausted();

        ScriptedDataSource terminal = new ScriptedDataSource(Step.query(
                "/*inx:task-cancel-state*/", row("status", "SUCCEEDED", "cancellation_requested", "N")));
        require(new JdbcTaskStore(terminal, JdbcDatabaseDialect.POSTGRESQL)
                        .requestCancellation(TASK_ID, NOW) == CancellationOutcome.ALREADY_TERMINAL,
                "terminal cancellation outcome is wrong");
        terminal.assertExhausted();

        ScriptedDataSource already = new ScriptedDataSource(Step.query(
                "/*inx:task-cancel-state*/", row("status", "RUNNING", "cancellation_requested", "Y")));
        require(new JdbcTaskStore(already, JdbcDatabaseDialect.POSTGRESQL)
                        .requestCancellation(TASK_ID, NOW) == CancellationOutcome.ALREADY_REQUESTED,
                "duplicate cancellation was not detected");
        already.assertExhausted();

        ScriptedDataSource running = new ScriptedDataSource(
                Step.query("/*inx:task-cancel-state*/", row("status", "RUNNING", "cancellation_requested", "N")),
                Step.update("/*inx:task-cancel*/", 1));
        require(new JdbcTaskStore(running, JdbcDatabaseDialect.POSTGRESQL)
                        .requestCancellation(TASK_ID, NOW) == CancellationOutcome.REQUESTED,
                "running cancellation was not requested");
        running.assertExhausted();

        ScriptedDataSource changed = new ScriptedDataSource(
                Step.query("/*inx:task-cancel-state*/", row("status", "PENDING", "cancellation_requested", "N")),
                Step.update("/*inx:task-cancel*/", 0));
        expect(IllegalStateException.class, () -> new JdbcTaskStore(
                changed, JdbcDatabaseDialect.POSTGRESQL).requestCancellation(TASK_ID, NOW));
        changed.assertExhausted();

        ScriptedDataSource missing = new ScriptedDataSource(
                Step.query("/*inx:task-read*/"),
                Step.query("/*inx:task-parameters*/"));
        require(new JdbcTaskStore(missing, JdbcDatabaseDialect.POSTGRESQL).find(TASK_ID).isEmpty(),
                "missing task was reported as present");
        missing.assertExhausted();

        Map<String, Object> presentRow = taskRowWithId(
                TASK_ID.value().value(), "PENDING", null, 1L, "N", "prior", null);
        ScriptedDataSource present = new ScriptedDataSource(
                Step.query("/*inx:task-read*/", presentRow),
                Step.query("/*inx:task-parameters*/", row(
                        "task_id", TASK_ID.value().value(),
                        "parameter_key", "payload",
                        "parameter_value", "alpha")));
        var found = new JdbcTaskStore(present, JdbcDatabaseDialect.POSTGRESQL).find(TASK_ID);
        require(found.isPresent() && found.orElseThrow().optionalFailure().orElseThrow().equals("prior"),
                "persisted task was not reconstructed");
        present.assertExhausted();

        Map<String, Object> checkpointed = taskRowWithId(
                TASK_ID.value().value(), "PENDING", null, 1L, "N", null,
                new Object[]{2L, "resume-2", NOW.minusSeconds(1)});
        ScriptedDataSource withCheckpoint = new ScriptedDataSource(
                Step.query("/*inx:task-read*/", checkpointed),
                Step.query("/*inx:task-parameters*/"));
        var record = new JdbcTaskStore(withCheckpoint, JdbcDatabaseDialect.POSTGRESQL)
                .find(TASK_ID).orElseThrow();
        require(record.optionalCheckpoint().orElseThrow().sequence() == 2L,
                "checkpoint was not reconstructed by find");
        withCheckpoint.assertExhausted();
    }

    static void provesCheckpointFailureDiagnostics() {
        ScriptedDataSource cancelled = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 0),
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "Y", 1)));
        expect(IllegalStateException.class, () -> new JdbcTaskStore(
                cancelled, JdbcDatabaseDialect.POSTGRESQL).saveCheckpoint(
                        TASK_ID, "worker-a", 1, "resume", NOW, Duration.ofSeconds(30)));
        cancelled.assertExhausted();

        ScriptedDataSource stale = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 0),
                Step.query("/*inx:task-lease-state*/", leaseState("worker-b", 2L, "N", 1)));
        expect(TaskLeaseLostException.class, () -> new JdbcTaskStore(
                stale, JdbcDatabaseDialect.POSTGRESQL).saveCheckpoint(
                        TASK_ID, "worker-a", 1, "resume", NOW, Duration.ofSeconds(30)));
        stale.assertExhausted();

        ScriptedDataSource disappeared = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 1),
                Step.query("/*inx:task-checkpoint-read*/"));
        expect(IllegalStateException.class, () -> new JdbcTaskStore(
                disappeared, JdbcDatabaseDialect.POSTGRESQL).saveCheckpoint(
                        TASK_ID, "worker-a", 1, "resume", NOW, Duration.ofSeconds(30)));
        disappeared.assertExhausted();

        ScriptedDataSource invalidSequence = new ScriptedDataSource(
                Step.update("/*inx:task-checkpoint*/", 1),
                Step.query("/*inx:task-checkpoint-read*/", row(
                        "checkpoint_sequence", "bad",
                        "checkpoint_token", "resume",
                        "checkpoint_at", NOW)));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                invalidSequence, JdbcDatabaseDialect.POSTGRESQL).saveCheckpoint(
                        TASK_ID, "worker-a", 1, "resume", NOW, Duration.ofSeconds(30)));
        invalidSequence.assertExhausted();

        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                new ScriptedDataSource(), JdbcDatabaseDialect.POSTGRESQL).saveCheckpoint(
                        TASK_ID, "worker-a", 1, "", NOW, Duration.ofSeconds(30)));
    }

    static void provesTransactionAndDataGuards() {
        ScriptedDataSource unknownLease = new ScriptedDataSource(
                Step.update("/*inx:task-renew*/", 0),
                Step.query("/*inx:task-lease-state*/"));
        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                unknownLease, JdbcDatabaseDialect.POSTGRESQL).renewLease(
                        TASK_ID, "worker-a", 1, NOW, Duration.ofSeconds(30)));
        unknownLease.assertExhausted();

        ScriptedDataSource badMarker = new ScriptedDataSource(Step.query(
                "/*inx:task-cancel-state*/", row("status", "RUNNING", "cancellation_requested", "X")));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                badMarker, JdbcDatabaseDialect.POSTGRESQL).requestCancellation(TASK_ID, NOW));
        badMarker.assertExhausted();

        Map<String, Object> badCheckpointRow = taskRowWithId(
                TASK_ID.value().value(), "PENDING", null, 1L, "N", null,
                new Object[]{"bad", "token", NOW});
        ScriptedDataSource badCheckpoint = new ScriptedDataSource(
                Step.query("/*inx:task-read*/", badCheckpointRow));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                badCheckpoint, JdbcDatabaseDialect.POSTGRESQL).find(TASK_ID));
        badCheckpoint.assertExhausted();

        ScriptedDataSource invalidLeaseVersion = new ScriptedDataSource(Step.update("/*inx:task-renew*/", 1));
        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                invalidLeaseVersion, JdbcDatabaseDialect.POSTGRESQL).renewLease(
                        TASK_ID, "worker-a", 0, NOW, Duration.ofSeconds(30)));
        invalidLeaseVersion.assertExhausted();

        ScriptedDataSource overflow = new ScriptedDataSource(Step.update("/*inx:task-renew*/", 1));
        expect(IllegalArgumentException.class, () -> new JdbcTaskStore(
                overflow, JdbcDatabaseDialect.POSTGRESQL).renewLease(
                        TASK_ID, "worker-a", 1, Instant.MAX, Duration.ofSeconds(1)));
        overflow.assertExhausted();

        ScriptedDataSource mutationFenced = new ScriptedDataSource(
                Step.update("/*inx:task-complete*/", 0),
                Step.query("/*inx:task-lease-state*/", leaseState("worker-b", 2L, "N", 1)));
        expect(TaskLeaseLostException.class, () -> new JdbcTaskStore(
                mutationFenced, JdbcDatabaseDialect.POSTGRESQL).markSucceeded(
                        TASK_ID, "worker-a", 1, NOW));
        mutationFenced.assertExhausted();

        ScriptedDataSource failureFenced = new ScriptedDataSource(
                Step.query("/*inx:task-lease-state*/", leaseState("worker-a", 1L, "N", 1)),
                Step.update("/*inx:task-fail*/", 0),
                Step.query("/*inx:task-lease-state*/", leaseState("worker-b", 2L, "N", 1)));
        expect(TaskLeaseLostException.class, () -> new JdbcTaskStore(
                failureFenced, JdbcDatabaseDialect.POSTGRESQL).markFailed(
                        TASK_ID, "worker-a", 1, NOW, retryPolicy(), new RuntimeException("lost")));
        failureFenced.assertExhausted();

        ScriptedDataSource savepointRestore = new ScriptedDataSource(
                Step.query("/*inx:task-idempotency*/"),
                Step.updateFailure("/*inx:task-insert*/", new SQLException("duplicate", "23505")))
                .failSavepointRollbackWith(new SQLException("savepoint rollback failed", "08006"));
        JdbcPersistenceException restoreFailure = expectAndGet(JdbcPersistenceException.class, () ->
                new JdbcTaskStore(savepointRestore, JdbcDatabaseDialect.POSTGRESQL).submit(
                        newTaskId(908), submission("alpha"), RetrySafety.RETRY_SAFE, NOW));
        require(restoreFailure.getCause() != null
                        && restoreFailure.getCause().getSuppressed().length == 1,
                "savepoint restore failure did not preserve the original SQL cause");
        savepointRestore.assertExhausted();

        ScriptedDataSource rollbackFailureSource = new ScriptedDataSource(Step.query(
                "/*inx:task-cancel-state*/", row("status", "RUNNING", "cancellation_requested", "X")))
                .failRollbackWith(new SQLException("rollback failed", "08006"));
        JdbcPersistenceException rollbackFailure = expectAndGet(JdbcPersistenceException.class, () ->
                new JdbcTaskStore(rollbackFailureSource, JdbcDatabaseDialect.POSTGRESQL)
                        .requestCancellation(TASK_ID, NOW));
        require(rollbackFailure.getCause() != null
                        && rollbackFailure.getCause().getSuppressed().length == 1,
                "transaction rollback failure was not attached to the primary cause");
        rollbackFailureSource.assertExhausted();

        ScriptedDataSource commitFailureSource = new ScriptedDataSource(
                Step.query("/*inx:task-cancel-state*/"))
                .failCommitWith(new SQLException("commit failed", "08006"));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                commitFailureSource, JdbcDatabaseDialect.POSTGRESQL).requestCancellation(TASK_ID, NOW));
        commitFailureSource.assertExhausted();

        ScriptedDataSource connectionFailureSource = new ScriptedDataSource()
                .failConnectionWith(new SQLException("database unavailable", "08001"));
        expect(JdbcPersistenceException.class, () -> new JdbcTaskStore(
                connectionFailureSource, JdbcDatabaseDialect.POSTGRESQL).find(TASK_ID));
    }

    private static TaskSubmission submission(String value) {
        return new TaskSubmission(new TaskType("core.test"), "idem-1", Map.of("payload", value), NOW);
    }

    private static TaskId newTaskId(int sequence) {
        String suffix = "%012d".formatted(sequence);
        return new TaskId(new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-" + suffix)));
    }

    private static ExponentialBackoffPolicy retryPolicy() {
        return new ExponentialBackoffPolicy(
                3, Duration.ofSeconds(2), Duration.ofSeconds(8), 0.0, () -> 0.0);
    }

    private static Map<String, Object> taskRow(
            String status, String owner, long leaseVersion, String cancelled, String failure) {
        return row(
                "task_id", TASK_ID.value().value(),
                "task_type", "core.test",
                "idempotency_key", "idem-1",
                "retry_safety", "RETRY_SAFE",
                "status", status,
                "attempts", 1,
                "available_at", NOW,
                "lease_owner", owner,
                "lease_version", leaseVersion,
                "lease_until", NOW.plusSeconds(30),
                "checkpoint_sequence", null,
                "checkpoint_token", null,
                "checkpoint_at", null,
                "cancellation_requested", cancelled,
                "last_failure", failure,
                "created_at", NOW.minusSeconds(1),
                "updated_at", NOW);
    }

    private static Map<String, Object> leaseState(String owner, long version, String cancelled, int attempts) {
        return row(
                "status", "RUNNING",
                "attempts", attempts,
                "retry_safety", "RETRY_SAFE",
                "available_at", NOW,
                "lease_owner", owner,
                "lease_version", version,
                "cancellation_requested", cancelled);
    }

    private static Map<String, Object> leaseState(
            String owner, long version, String cancelled, int attempts, String retrySafety) {
        return row(
                "status", "RUNNING",
                "attempts", attempts,
                "retry_safety", retrySafety,
                "available_at", NOW,
                "lease_owner", owner,
                "lease_version", version,
                "cancellation_requested", cancelled);
    }

    private static Map<String, Object> expiredLease(
            String retrySafety, int attempts, long leaseVersion, String cancelled) {
        return row(
                "task_id", TASK_ID.value().value(),
                "retry_safety", retrySafety,
                "attempts", attempts,
                "available_at", NOW.minusSeconds(20),
                "lease_version", leaseVersion,
                "cancellation_requested", cancelled);
    }

    private static Map<String, Object> taskRowWithId(
            Object taskId,
            String status,
            String owner,
            long leaseVersion,
            String cancelled,
            String failure,
            Object[] checkpoint) {
        Object checkpointSequence = checkpoint == null ? null : checkpoint[0];
        Object checkpointToken = checkpoint == null ? null : checkpoint[1];
        Object checkpointAt = checkpoint == null ? null : checkpoint[2];
        return row(
                "task_id", taskId,
                "task_type", "core.test",
                "idempotency_key", "idem-1",
                "retry_safety", "RETRY_SAFE",
                "status", status,
                "attempts", status.equals("RUNNING") ? 1 : 0,
                "available_at", NOW,
                "lease_owner", owner,
                "lease_version", leaseVersion,
                "lease_until", owner == null ? null : NOW.plusSeconds(30),
                "checkpoint_sequence", checkpointSequence,
                "checkpoint_token", checkpointToken,
                "checkpoint_at", checkpointAt,
                "cancellation_requested", cancelled,
                "last_failure", failure,
                "created_at", NOW.minusSeconds(1),
                "updated_at", NOW);
    }

    private static Map<String, Object> row(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("row requires key/value pairs");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            row.put((String) entries[index], entries[index + 1]);
        }
        return row;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) {
        expectAndGet(type, action);
    }

    private static <T extends Throwable> T expectAndGet(Class<T> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record Step(
            String marker,
            List<Map<String, Object>> rows,
            Integer updateCount,
            int[] batchCounts,
            SQLException updateFailure) {
        static Step query(String marker) {
            return new Step(marker, List.of(), null, null, null);
        }

        static Step query(String marker, Map<String, Object> row) {
            return new Step(marker, List.of(row), null, null, null);
        }

        static Step update(String marker, int count) {
            return new Step(marker, List.of(), count, null, null);
        }

        static Step batch(String marker, int... counts) {
            return new Step(marker, List.of(), null, counts.clone(), null);
        }

        static Step updateFailure(String marker, SQLException failure) {
            return new Step(marker, List.of(), null, null, failure);
        }
    }

    private static final class ScriptedDataSource implements DataSource {
        private final Queue<Step> steps = new ArrayDeque<>();
        private final AtomicInteger savepointIds = new AtomicInteger();
        private final List<String> observedSql = new java.util.ArrayList<>();
        private SQLException connectionFailure;
        private SQLException savepointRollbackFailure;
        private SQLException rollbackFailure;
        private SQLException commitFailure;

        ScriptedDataSource(Step... configured) {
            steps.addAll(List.of(configured));
        }

        void assertExhausted() {
            require(steps.isEmpty(), "unconsumed JDBC step: " + steps.peek());
        }

        List<String> observedSql() {
            return List.copyOf(observedSql);
        }

        ScriptedDataSource failConnectionWith(SQLException failure) {
            connectionFailure = failure;
            return this;
        }

        ScriptedDataSource failSavepointRollbackWith(SQLException failure) {
            savepointRollbackFailure = failure;
            return this;
        }

        ScriptedDataSource failRollbackWith(SQLException failure) {
            rollbackFailure = failure;
            return this;
        }

        ScriptedDataSource failCommitWith(SQLException failure) {
            commitFailure = failure;
            return this;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (connectionFailure != null) {
                throw connectionFailure;
            }
            return connectionProxy();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private Connection connectionProxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statementProxy((String) args[0]);
                        case "setSavepoint" -> savepoint(savepointIds.incrementAndGet());
                        case "rollback" -> {
                            if (args != null && args.length == 1 && savepointRollbackFailure != null) {
                                throw savepointRollbackFailure;
                            }
                            if ((args == null || args.length == 0) && rollbackFailure != null) {
                                throw rollbackFailure;
                            }
                            yield null;
                        }
                        case "commit" -> {
                            if (commitFailure != null) {
                                throw commitFailure;
                            }
                            yield null;
                        }
                        case "releaseSavepoint", "setAutoCommit", "setTransactionIsolation", "close" -> null;
                        case "getAutoCommit" -> false;
                        case "isClosed" -> false;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }


        private static Savepoint savepoint(int id) {
            return new Savepoint() {
                @Override
                public int getSavepointId() {
                    return id;
                }

                @Override
                public String getSavepointName() {
                    return "inx-" + id;
                }
            };
        }

        private PreparedStatement statementProxy(String sql) {
            observedSql.add(sql);
            Step step = steps.poll();
            if (step == null) {
                throw new AssertionError("unexpected SQL: " + sql);
            }
            require(sql.contains(step.marker()), "expected SQL marker " + step.marker() + " but got " + sql);
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeQuery" -> resultSetProxy(step.rows());
                        case "executeUpdate" -> {
                            if (step.updateFailure() != null) {
                                throw step.updateFailure();
                            }
                            yield step.updateCount() == null ? 0 : step.updateCount();
                        }
                        case "executeBatch" -> step.batchCounts() == null ? new int[0] : step.batchCounts().clone();
                        case "setString", "setObject", "setNull", "setInt", "setLong", "setMaxRows", "setCharacterStream", "addBatch", "close" -> null;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static ResultSet resultSetProxy(List<Map<String, Object>> rows) {
            AtomicInteger cursor = new AtomicInteger(-1);
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("next")) {
                            return cursor.incrementAndGet() < rows.size();
                        }
                        if (name.equals("close")) {
                            return null;
                        }
                        if (name.equals("unwrap")) {
                            return null;
                        }
                        if (name.equals("isWrapperFor")) {
                            return false;
                        }
                        Map<String, Object> row = rows.get(cursor.get());
                        Object value = row.get((String) args[0]);
                        return switch (name) {
                            case "getObject" -> value;
                            case "getString" -> value == null ? null : value.toString();
                            case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            if (type == char.class) {
                return '\0';
            }
            return null;
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("JdbcTaskStoreSmoke"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
