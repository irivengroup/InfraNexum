package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.workers.InMemoryTaskStore;
import io.infranexum.core.workers.RetrySafety;
import io.infranexum.core.workers.TaskExecutionContext;
import io.infranexum.core.workers.TaskHandler;
import io.infranexum.core.workers.TaskHandlerRegistry;
import io.infranexum.core.workers.TaskScheduler;
import io.infranexum.core.workers.TaskSubmission;
import io.infranexum.core.workers.TaskType;
import io.infranexum.core.workers.TaskWorker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class WorkerCorrelationBridgeTest {
    private static final DomainIdentifier CORRELATION =
            DomainIdentifier.parse("018bcfe5-6800-7001-8000-000000000001");
    private static final TaskType TYPE = new TaskType("observability.correlation");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capturesValidatedMdcDurablyAndRestoresItAroundWorkerExecution() {
        WorkerCorrelationBridge bridge = new WorkerCorrelationBridge();
        InMemoryTaskStore store = new InMemoryTaskStore();
        AtomicReference<String> observedMdc = new AtomicReference<>();
        AtomicReference<DomainIdentifier> observedContext = new AtomicReference<>();
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TYPE;
            }

            @Override
            public RetrySafety retrySafety() {
                return RetrySafety.RETRY_SAFE;
            }

            @Override
            public void execute(TaskExecutionContext context) {
                observedMdc.set(MDC.get(CorrelationContext.MDC_KEY));
                observedContext.set(context.correlationId().orElseThrow());
            }
        };
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        TaskScheduler scheduler = new TaskScheduler(
                store, registry, new UuidV7Generator(), CLOCK, bridge);

        MDC.put(CorrelationContext.MDC_KEY, CORRELATION.toString());
        var result = scheduler.schedule(new TaskSubmission(TYPE, "correlated", Map.of(), CLOCK.instant()));
        assertEquals(CORRELATION, store.find(result.taskId()).orElseThrow().correlationId());

        MDC.remove(CorrelationContext.MDC_KEY);
        TaskWorker worker = new TaskWorker(
                store,
                registry,
                retryPolicy(),
                CLOCK,
                "worker-correlation-test",
                Duration.ofSeconds(30),
                () -> false,
                bridge);
        assertEquals(1, worker.runOnce().succeeded());
        assertEquals(CORRELATION.toString(), observedMdc.get());
        assertEquals(CORRELATION, observedContext.get());
        assertNull(MDC.get(CorrelationContext.MDC_KEY));
    }

    @Test
    void rejectsCorruptInternalMdcInsteadOfDroppingCorrelationSilently() {
        WorkerCorrelationBridge bridge = new WorkerCorrelationBridge();
        MDC.put(CorrelationContext.MDC_KEY, "not-a-domain-identifier");
        assertThrows(IllegalStateException.class, bridge::current);
    }

    private static RetryPolicy retryPolicy() {
        return new RetryPolicy() {
            @Override
            public int maximumAttempts() {
                return 3;
            }

            @Override
            public Duration delayAfterFailure(int attempts) {
                return Duration.ofSeconds(1);
            }
        };
    }
}
