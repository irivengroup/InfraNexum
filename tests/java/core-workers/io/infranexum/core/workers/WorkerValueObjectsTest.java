package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.START;
import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static io.infranexum.core.workers.WorkerTestFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Boundary and invariant tests for immutable worker contracts. */
final class WorkerValueObjectsTest {
    @Test
    void taskTypeAndTaskIdentifierAreStrictAndComparable() {
        assertEquals("inventory.refresh", new TaskType(" inventory.refresh ").value());
        assertTrue(new TaskType("a").compareTo(new TaskType("b")) < 0);
        assertThrows(IllegalArgumentException.class, () -> new TaskType(" "));
        assertThrows(IllegalArgumentException.class, () -> new TaskType("Inventory.Refresh"));
        assertThrows(IllegalArgumentException.class, () -> new TaskType("a..b"));
        assertThrows(IllegalArgumentException.class, () -> new TaskType("a".repeat(161)));
        assertEquals(id(1), TaskId.parse(id(1).toString()));
        assertTrue(id(1).compareTo(id(2)) < 0);
    }

    @Test
    void submissionNormalizesAndDefensivelyCopiesParameters() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("z", "2");
        mutable.put("a", "1");
        TaskSubmission submission = new TaskSubmission(TYPE, "  key  ", mutable, START);
        mutable.put("late", "mutation");

        assertEquals("key", submission.idempotencyKey());
        assertEquals(Map.of("a", "1", "z", "2"), submission.parameters());
        assertFalse(submission.parameters().containsKey("late"));
        assertThrows(UnsupportedOperationException.class, () -> submission.parameters().put("x", "y"));
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(TYPE, " ", Map.of(), START));
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(TYPE, "key", Map.of("1bad", "x"), START));
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(TYPE, "key", Map.of("ok", "x".repeat(4_097)), START));

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            tooMany.put("p" + index, "v");
        }
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(TYPE, "key", tooMany, START));
    }

    @Test
    void checkpointAndTaskRecordEnforceLeaseInvariants() {
        TaskCheckpoint checkpoint = new TaskCheckpoint(1, "resume", START);
        assertEquals("resume", checkpoint.token());
        assertThrows(IllegalArgumentException.class, () -> new TaskCheckpoint(0, "resume", START));
        assertThrows(IllegalArgumentException.class, () -> new TaskCheckpoint(1, "", START));

        TaskRecord pending = record(TaskStatus.PENDING, null, 0, null);
        assertTrue(pending.optionalCheckpoint().isEmpty());
        assertTrue(pending.optionalFailure().isEmpty());
        TaskRecord running = record(TaskStatus.RUNNING, "worker", 1, START.plusSeconds(10));
        assertEquals("worker", running.leaseOwner());
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.PENDING, "worker", 1, START));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.RUNNING, "worker", 0, START));
        assertThrows(IllegalArgumentException.class, () -> new TaskRecord(
                id(2), TYPE, "key", Map.of(), RetrySafety.RETRY_SAFE, TaskStatus.PENDING, -1, START,
                null, 0, null, null, false, null, START, START));
        assertThrows(IllegalArgumentException.class, () -> new TaskRecord(
                id(2), TYPE, "key", Map.of(), RetrySafety.RETRY_SAFE, TaskStatus.PENDING, 0, START,
                null, -1, null, null, false, null, START, START));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.RUNNING, " ", 1, START));
        assertThrows(IllegalArgumentException.class, () -> record(TaskStatus.RUNNING, "w".repeat(161), 1, START));
        assertThrows(IllegalArgumentException.class, () -> new TaskRecord(
                id(2), TYPE, "key", Map.of(), RetrySafety.RETRY_SAFE, TaskStatus.PENDING, 0, START,
                null, 0, null, null, false, "x".repeat(1_025), START, START));
    }

    @Test
    void workerReportsAndConfigurationRejectInvalidState() {
        WorkerIterationReport idle = WorkerIterationReport.idle();
        assertEquals(0, idle.claimed());
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(0, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(0, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(0, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerIterationReport(0, 0, 0, 0, 0, -1));

        WorkerPoolConfiguration configuration = new WorkerPoolConfiguration(
                2,
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ofSeconds(2));
        assertEquals(2, configuration.concurrency());
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolConfiguration(
                0, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolConfiguration(
                257, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolConfiguration(
                1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolConfiguration(
                1, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolConfiguration(
                1, Duration.ofDays(31), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    @Test
    void workerPoolSnapshotIsStrictAndReadinessRequiresCompleteLiveAndStoreCapacity() {
        WorkerPoolSnapshot ready = new WorkerPoolSnapshot(
                WorkerPoolState.RUNNING, 2, 2, 2, 1, 5, 2, 1, 1, 0, 1, 0, 0);
        assertTrue(ready.ready());
        assertFalse(new WorkerPoolSnapshot(
                WorkerPoolState.RUNNING, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0).ready());
        assertFalse(new WorkerPoolSnapshot(
                WorkerPoolState.RUNNING, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0).ready());
        assertFalse(new WorkerPoolSnapshot(
                WorkerPoolState.STOPPING, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0).ready());
        assertFalse(new WorkerPoolSnapshot(
                WorkerPoolState.RUNNING, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1).ready());

        assertThrows(NullPointerException.class, () -> new WorkerPoolSnapshot(
                null, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPoolSnapshot(
                WorkerPoolState.NEW, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0));
    }

    @Test
    void shutdownReportCannotClaimAFalseTermination() {
        ShutdownReport graceful = new ShutdownReport(true, false, true, 2, Duration.ZERO);
        ShutdownReport forcedIncomplete = new ShutdownReport(false, true, false, 2, Duration.ofSeconds(1));
        assertTrue(graceful.terminated());
        assertFalse(forcedIncomplete.terminated());
        assertThrows(IllegalArgumentException.class, () -> new ShutdownReport(true, false, false, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ShutdownReport(true, true, true, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ShutdownReport(false, true, true, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ShutdownReport(false, true, true, 1, Duration.ofSeconds(-1)));
    }

    private static TaskRecord record(TaskStatus status, String leaseOwner, long leaseVersion, java.time.Instant leaseUntil) {
        return new TaskRecord(
                id(1), TYPE, "key", Map.of(), RetrySafety.RETRY_SAFE, status, 0, START,
                leaseOwner, leaseVersion, leaseUntil, null, false, null, START, START);
    }
}
