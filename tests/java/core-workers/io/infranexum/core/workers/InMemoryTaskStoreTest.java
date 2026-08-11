package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.LEASE;
import static io.infranexum.core.workers.WorkerTestFixtures.RETRY;
import static io.infranexum.core.workers.WorkerTestFixtures.START;
import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static io.infranexum.core.workers.WorkerTestFixtures.id;
import static io.infranexum.core.workers.WorkerTestFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Atomicity, idempotency, lease fencing and recovery contracts for the reference store. */
final class InMemoryTaskStoreTest {
    @Test
    void submissionIsIdempotentAndRejectsSemanticReuse() {
        InMemoryTaskStore store = new InMemoryTaskStore(2);
        TaskSubmission first = submission("same-key");

        TaskSubmissionResult created = store.submit(id(1), first, RetrySafety.RETRY_SAFE, START);
        TaskSubmissionResult replay = store.submit(id(2), first, RetrySafety.RETRY_SAFE, START.plusSeconds(1));

        assertTrue(created.created());
        assertFalse(replay.created());
        assertEquals(created.taskId(), replay.taskId());
        assertThrows(
                IdempotencyConflictException.class,
                () -> store.submit(
                        id(3),
                        new TaskSubmission(TYPE, "same-key", Map.of("site", "lyon"), START),
                        RetrySafety.RETRY_SAFE,
                        START));
        assertThrows(
                IdempotencyConflictException.class,
                () -> store.submit(id(3), first, RetrySafety.AT_MOST_ONCE, START));

        store.submit(id(4), submission("second"), RetrySafety.RETRY_SAFE, START);
        assertThrows(
                TaskCapacityExceededException.class,
                () -> store.submit(id(5), submission("third"), RetrySafety.RETRY_SAFE, START));
    }

    @Test
    void claimsAreBoundedOrderedAndLeaseFenced() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskSubmission delayed = new TaskSubmission(TYPE, "later", Map.of(), START.plusSeconds(20));
        store.submit(id(3), delayed, RetrySafety.RETRY_SAFE, START);
        store.submit(id(2), submission("b"), RetrySafety.RETRY_SAFE, START);
        store.submit(id(1), submission("a"), RetrySafety.RETRY_SAFE, START);

        List<TaskRecord> claimed = store.claimBatch("worker-a", 2, START, LEASE, RETRY);

        assertEquals(2, claimed.size());
        assertEquals(id(1), claimed.get(0).taskId());
        assertEquals(id(2), claimed.get(1).taskId());
        assertEquals(1, claimed.get(0).attempts());
        assertEquals(1, claimed.get(0).leaseVersion());
        assertEquals(START.plus(LEASE), claimed.get(0).leaseUntil());
        assertTrue(store.claimBatch("worker-b", 1, START, LEASE, RETRY).isEmpty());

        TaskRecord task = claimed.get(0);
        assertThrows(
                TaskLeaseLostException.class,
                () -> store.renewLease(task.taskId(), "worker-b", task.leaseVersion(), START, LEASE));
        assertThrows(
                TaskLeaseLostException.class,
                () -> store.markSucceeded(task.taskId(), "worker-a", task.leaseVersion() + 1, START));

        store.renewLease(task.taskId(), "worker-a", task.leaseVersion(), START.plusSeconds(2), LEASE);
        assertEquals(START.plusSeconds(12), store.find(task.taskId()).orElseThrow().leaseUntil());
    }

    @Test
    void checkpointRenewsLeaseAndCancellationIsFailClosed() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("checkpoint"), RetrySafety.RETRY_SAFE, START);
        TaskRecord task = store.claimBatch("worker-a", 1, START, LEASE, RETRY).getFirst();

        TaskCheckpoint first = store.saveCheckpoint(
                task.taskId(), "worker-a", task.leaseVersion(), "page=10", START.plusSeconds(1), LEASE);
        TaskCheckpoint second = store.saveCheckpoint(
                task.taskId(), "worker-a", task.leaseVersion(), "page=20", START.plusSeconds(2), LEASE);

        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals("page=20", store.find(task.taskId()).orElseThrow().checkpoint().token());
        assertEquals(START.plusSeconds(12), store.find(task.taskId()).orElseThrow().leaseUntil());
        assertEquals(CancellationOutcome.REQUESTED, store.requestCancellation(task.taskId(), START.plusSeconds(3)));
        assertEquals(CancellationOutcome.ALREADY_REQUESTED, store.requestCancellation(task.taskId(), START.plusSeconds(4)));
        assertThrows(
                IllegalStateException.class,
                () -> store.saveCheckpoint(
                        task.taskId(), "worker-a", task.leaseVersion(), "late", START.plusSeconds(5), LEASE));
        store.markCancelled(task.taskId(), "worker-a", task.leaseVersion(), START.plusSeconds(5));
        assertEquals(TaskStatus.CANCELLED, store.find(task.taskId()).orElseThrow().status());
        assertEquals(CancellationOutcome.ALREADY_TERMINAL, store.requestCancellation(task.taskId(), START.plusSeconds(6)));
        assertEquals(CancellationOutcome.NOT_FOUND, store.requestCancellation(id(99), START));
    }

    @Test
    void retrySafeFailureBacksOffAndStopsAtMaximumAttempts() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("retry"), RetrySafety.RETRY_SAFE, START);

        TaskRecord first = store.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();
        TaskStatus firstStatus = store.markFailed(
                first.taskId(), "worker", first.leaseVersion(), START, RETRY, new IllegalStateException("transient"));
        assertEquals(TaskStatus.PENDING, firstStatus);
        assertTrue(store.claimBatch("worker", 1, START.plusSeconds(4), LEASE, RETRY).isEmpty());

        TaskRecord second = store.claimBatch("worker", 1, START.plusSeconds(5), LEASE, RETRY).getFirst();
        assertEquals(2, second.attempts());
        store.markFailed(second.taskId(), "worker", second.leaseVersion(), START.plusSeconds(5), RETRY, new RuntimeException("again"));
        TaskRecord third = store.claimBatch("worker", 1, START.plusSeconds(10), LEASE, RETRY).getFirst();
        assertEquals(3, third.attempts());
        assertEquals(
                TaskStatus.FAILED,
                store.markFailed(third.taskId(), "worker", third.leaseVersion(), START.plusSeconds(10), RETRY, new RuntimeException("terminal")));
        TaskRecord failed = store.find(id(1)).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertTrue(failed.optionalFailure().orElseThrow().contains("terminal"));
    }

    @Test
    void atMostOnceTaskIsNeverAutomaticallyRetried() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("unsafe"), RetrySafety.AT_MOST_ONCE, START);
        TaskRecord task = store.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();

        assertEquals(
                TaskStatus.FAILED,
                store.markFailed(task.taskId(), "worker", task.leaseVersion(), START, RETRY, new RuntimeException("unknown")));
        assertTrue(store.claimBatch("worker", 1, START.plusSeconds(30), LEASE, RETRY).isEmpty());
    }

    @Test
    void expiredLeaseUsesRetrySafetyAndCancellationToRecoverDeterministically() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("safe"), RetrySafety.RETRY_SAFE, START);
        store.submit(id(2), submission("unsafe"), RetrySafety.AT_MOST_ONCE, START);
        store.submit(id(3), submission("cancelled"), RetrySafety.RETRY_SAFE, START);
        List<TaskRecord> leased = store.claimBatch("worker-a", 3, START, LEASE, RETRY);
        store.requestCancellation(id(3), START.plusSeconds(1));

        assertTrue(store.claimBatch("worker-b", 3, START.plus(LEASE), LEASE, RETRY).isEmpty());
        TaskRecord safe = store.find(id(1)).orElseThrow();
        TaskRecord unsafe = store.find(id(2)).orElseThrow();
        TaskRecord cancelled = store.find(id(3)).orElseThrow();

        assertEquals(3, leased.size());
        assertEquals(TaskStatus.PENDING, safe.status());
        assertEquals(START.plusSeconds(15), safe.availableAt());
        assertEquals(TaskStatus.FAILED, unsafe.status());
        assertTrue(unsafe.optionalFailure().orElseThrow().contains("automatic retry forbidden"));
        assertEquals(TaskStatus.CANCELLED, cancelled.status());

        TaskRecord recovered = store.claimBatch("worker-b", 1, START.plusSeconds(15), LEASE, RETRY).getFirst();
        assertEquals(id(1), recovered.taskId());
        assertEquals(2, recovered.attempts());
        assertTrue(recovered.leaseVersion() > leased.get(0).leaseVersion());
        assertThrows(
                TaskLeaseLostException.class,
                () -> store.markSucceeded(id(1), "worker-a", leased.get(0).leaseVersion(), START.plusSeconds(15)));
    }

    @Test
    void terminalFailureSanitizesAndBoundsDiagnosticText() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.submit(id(1), submission("failure"), RetrySafety.RETRY_SAFE, START);
        TaskRecord task = store.claimBatch("worker", 1, START, LEASE, RETRY).getFirst();
        String oversized = "x".repeat(2_000);

        store.markTerminalFailure(task.taskId(), "worker", task.leaseVersion(), START, new RuntimeException(oversized));

        TaskRecord failed = store.find(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.status());
        assertNotNull(failed.lastFailure());
        assertEquals(1_024, failed.lastFailure().length());
    }

    @Test
    void validationRejectsUnsafeStoreOperations() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTaskStore(0));
        InMemoryTaskStore store = new InMemoryTaskStore();
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("worker", 0, START, LEASE, RETRY));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("worker", 1_001, START, LEASE, RETRY));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch(" ", 1, START, LEASE, RETRY));
        assertThrows(IllegalArgumentException.class, () -> store.claimBatch("worker", 1, START, Duration.ZERO, RETRY));
    }
}
