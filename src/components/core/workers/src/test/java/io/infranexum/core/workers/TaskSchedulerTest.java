package io.infranexum.core.workers;

import static io.infranexum.core.workers.WorkerTestFixtures.START;
import static io.infranexum.core.workers.WorkerTestFixtures.TYPE;
import static io.infranexum.core.workers.WorkerTestFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Scheduling facade tests covering handler registration and idempotent replay. */
final class TaskSchedulerTest {
    @Test
    void schedulerUsesHandlerRetryContractAndIdempotentStore() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskHandler handler = handler(TYPE, RetrySafety.RETRY_SAFE);
        TaskScheduler scheduler = new TaskScheduler(
                store,
                new TaskHandlerRegistry(List.of(handler)),
                new UuidV7Generator(clock, new SecureRandom(new byte[] {1, 2, 3, 4})),
                clock);

        TaskSubmissionResult first = scheduler.schedule(submission("schedule-key"));
        TaskSubmissionResult replay = scheduler.schedule(submission("schedule-key"));

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.taskId(), replay.taskId());
        assertEquals(RetrySafety.RETRY_SAFE, scheduler.find(first.taskId()).orElseThrow().retrySafety());
        assertEquals(CancellationOutcome.REQUESTED, scheduler.cancel(first.taskId()));
        assertEquals(TaskStatus.CANCELLED, scheduler.find(first.taskId()).orElseThrow().status());
    }

    @Test
    void schedulerRejectsUnregisteredTaskType() {
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        TaskScheduler scheduler = new TaskScheduler(
                new InMemoryTaskStore(),
                new TaskHandlerRegistry(List.of()),
                new UuidV7Generator(clock, new SecureRandom()),
                clock);

        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(submission("unknown")));
    }

    @Test
    void registryRejectsDuplicateHandlersAndExposesLookup() {
        TaskHandler one = handler(TYPE, RetrySafety.RETRY_SAFE);
        TaskHandler two = handler(TYPE, RetrySafety.AT_MOST_ONCE);

        assertThrows(IllegalArgumentException.class, () -> new TaskHandlerRegistry(List.of(one, two)));
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(one));
        assertEquals(1, registry.size());
        assertTrue(registry.find(TYPE).isPresent());
        assertEquals(one, registry.require(TYPE));
        assertThrows(IllegalArgumentException.class, () -> registry.require(new TaskType("other.task")));
    }

    private static TaskHandler handler(TaskType type, RetrySafety safety) {
        return new TaskHandler() {
            @Override
            public TaskType taskType() {
                return type;
            }

            @Override
            public RetrySafety retrySafety() {
                return safety;
            }

            @Override
            public void execute(TaskExecutionContext context) {
                // Scheduling tests do not execute handlers.
            }
        };
    }
}
