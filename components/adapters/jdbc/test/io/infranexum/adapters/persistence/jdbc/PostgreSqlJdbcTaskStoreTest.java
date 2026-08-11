package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.workers.CancellationOutcome;
import io.infranexum.core.workers.IdempotencyConflictException;
import io.infranexum.core.workers.RetrySafety;
import io.infranexum.core.workers.TaskId;
import io.infranexum.core.workers.TaskLeaseLostException;
import io.infranexum.core.workers.TaskRecord;
import io.infranexum.core.workers.TaskStatus;
import io.infranexum.core.workers.TaskSubmission;
import io.infranexum.core.workers.TaskType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL contract tests for durable worker scheduling, claims and lease fencing. */
class PostgreSqlJdbcTaskStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-10T16:00:00Z");

    private PGSimpleDataSource dataSource;
    private JdbcTaskStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        store = new JdbcTaskStore(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) {
            truncate();
        }
    }

    @Test
    void submitIsIdempotentAndRejectsSemanticDrift() {
        TaskId firstId = taskId(1);
        TaskSubmission submission = submission("idem-a", "alpha", NOW);
        var first = store.submit(firstId, submission, RetrySafety.RETRY_SAFE, NOW);
        var replay = store.submit(taskId(2), submission, RetrySafety.RETRY_SAFE, NOW.plusMillis(1));

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(firstId, replay.taskId());
        assertEquals(Map.of("payload", "alpha"), store.find(firstId).orElseThrow().parameters());
        assertThrows(IdempotencyConflictException.class, () -> store.submit(
                taskId(3), submission("idem-a", "changed", NOW), RetrySafety.RETRY_SAFE, NOW));
    }

    @Test
    void concurrentWorkersClaimEveryDueTaskAtMostOnce() throws Exception {
        for (int index = 100; index < 140; index++) {
            store.submit(taskId(index), submission("idem-" + index, "v-" + index, NOW), RetrySafety.RETRY_SAFE, NOW);
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<TaskRecord>>> claims = List.of(
                    () -> store.claimBatch("worker-a", 10, NOW.plusSeconds(1), Duration.ofMinutes(1), retryPolicy()),
                    () -> store.claimBatch("worker-b", 10, NOW.plusSeconds(1), Duration.ofMinutes(1), retryPolicy()),
                    () -> store.claimBatch("worker-c", 10, NOW.plusSeconds(1), Duration.ofMinutes(1), retryPolicy()),
                    () -> store.claimBatch("worker-d", 10, NOW.plusSeconds(1), Duration.ofMinutes(1), retryPolicy()));
            Set<TaskId> unique = new HashSet<>();
            for (var future : executor.invokeAll(claims)) {
                for (TaskRecord record : future.get()) {
                    assertTrue(unique.add(record.taskId()), "task was claimed by more than one worker");
                    assertEquals(TaskStatus.RUNNING, record.status());
                    assertEquals(1, record.attempts());
                    assertEquals(1, record.leaseVersion());
                }
            }
            assertEquals(40, unique.size());
        }
    }

    @Test
    void checkpointRetryAndLeaseFencingAreAtomic() {
        TaskId id = taskId(200);
        store.submit(id, submission("idem-200", "payload", NOW), RetrySafety.RETRY_SAFE, NOW);
        TaskRecord claimed = store.claimBatch("owner", 1, NOW, Duration.ofSeconds(10), retryPolicy()).getFirst();

        var checkpoint = store.saveCheckpoint(id, "owner", claimed.leaseVersion(), "resume-1", NOW.plusSeconds(1), Duration.ofSeconds(10));
        assertEquals(1, checkpoint.sequence());
        assertThrows(TaskLeaseLostException.class, () -> store.renewLease(
                id, "intruder", claimed.leaseVersion(), NOW.plusSeconds(2), Duration.ofSeconds(10)));

        assertEquals(TaskStatus.PENDING, store.markFailed(
                id, "owner", claimed.leaseVersion(), NOW.plusSeconds(2), retryPolicy(), new SQLException("temporary")));
        assertTrue(store.claimBatch("owner", 1, NOW.plusSeconds(3), Duration.ofSeconds(10), retryPolicy()).isEmpty());
        TaskRecord retry = store.claimBatch("owner", 1, NOW.plusSeconds(4), Duration.ofSeconds(10), retryPolicy()).getFirst();
        assertEquals(2, retry.attempts());
        assertEquals(2, retry.leaseVersion());
        assertEquals(1, retry.optionalCheckpoint().orElseThrow().sequence());
        store.markSucceeded(id, "owner", retry.leaseVersion(), NOW.plusSeconds(5));
        assertEquals(TaskStatus.SUCCEEDED, store.find(id).orElseThrow().status());
    }

    @Test
    void expiredAtMostOnceLeaseBecomesTerminalWithoutReexecution() {
        TaskId id = taskId(300);
        store.submit(id, submission("idem-300", "payload", NOW), RetrySafety.AT_MOST_ONCE, NOW);
        store.claimBatch("worker-a", 1, NOW, Duration.ofSeconds(1), retryPolicy());

        assertTrue(store.claimBatch("worker-b", 1, NOW.plusSeconds(2), Duration.ofSeconds(10), retryPolicy()).isEmpty());
        TaskRecord failed = store.find(id).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals(1, failed.attempts());
        assertTrue(failed.optionalFailure().orElseThrow().contains("outcome unknown"));
    }

    @Test
    void cancellationIsImmediateForPendingAndCooperativeForRunning() {
        TaskId pending = taskId(400);
        store.submit(pending, submission("idem-400", "pending", NOW.plusSeconds(30)), RetrySafety.RETRY_SAFE, NOW);
        assertEquals(CancellationOutcome.REQUESTED, store.requestCancellation(pending, NOW.plusSeconds(1)));
        assertEquals(TaskStatus.CANCELLED, store.find(pending).orElseThrow().status());
        assertEquals(CancellationOutcome.ALREADY_TERMINAL, store.requestCancellation(pending, NOW.plusSeconds(2)));

        TaskId running = taskId(401);
        store.submit(running, submission("idem-401", "running", NOW), RetrySafety.RETRY_SAFE, NOW);
        TaskRecord lease = store.claimBatch("worker", 1, NOW, Duration.ofSeconds(10), retryPolicy()).getFirst();
        assertEquals(CancellationOutcome.REQUESTED, store.requestCancellation(running, NOW.plusSeconds(1)));
        assertTrue(store.find(running).orElseThrow().cancellationRequested());
        store.markCancelled(running, "worker", lease.leaseVersion(), NOW.plusSeconds(2));
        assertEquals(TaskStatus.CANCELLED, store.find(running).orElseThrow().status());
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE TABLE infranexum_core.worker_task_parameter, infranexum_core.worker_task")) {
            statement.executeUpdate();
        }
    }

    private static TaskSubmission submission(String key, String payload, Instant notBefore) {
        return new TaskSubmission(new TaskType("core.test"), key, Map.of("payload", payload), notBefore);
    }

    private static TaskId taskId(int sequence) {
        String suffix = "%012d".formatted(sequence);
        return new TaskId(new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-" + suffix)));
    }

    private static ExponentialBackoffPolicy retryPolicy() {
        return new ExponentialBackoffPolicy(
                3, Duration.ofSeconds(2), Duration.ofSeconds(8), 0.0, () -> 0.0);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
