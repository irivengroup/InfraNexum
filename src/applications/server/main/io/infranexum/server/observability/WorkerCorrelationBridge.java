package io.infranexum.server.observability;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.workers.TaskCorrelationProvider;
import io.infranexum.core.workers.TaskExecutionContext;
import io.infranexum.core.workers.TaskExecutionScopeFactory;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Bridges validated HTTP correlation into durable task scheduling and restores it around handlers.
 *
 * <p>Only the canonical correlation identifier crosses the asynchronous boundary. Raw request
 * headers, security context and arbitrary MDC entries are deliberately not propagated.
 */
public final class WorkerCorrelationBridge implements TaskCorrelationProvider, TaskExecutionScopeFactory {
    private static final String WORKER_SPAN_NAME = "infranexum.worker.execute";
    private static final String TASK_TYPE_TAG = "infranexum.worker.task.type";
    private static final String CORRELATION_TAG = "infranexum.correlation.id";

    private final Tracer tracer;

    /** Creates a no-op tracing bridge for isolated Core/contract tests. */
    public WorkerCorrelationBridge() {
        this(Tracer.NOOP);
    }

    /** Creates the Server bridge using the auto-configured Micrometer/OpenTelemetry tracer. */
    public WorkerCorrelationBridge(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public Optional<DomainIdentifier> current() {
        String value = MDC.get(CorrelationContext.MDC_KEY);
        if (value == null) {
            return Optional.empty();
        }
        try {
            DomainIdentifier identifier = DomainIdentifier.parse(value);
            if (!identifier.toString().equals(value)) {
                throw new IllegalStateException("correlation MDC is not canonical UUIDv7");
            }
            return Optional.of(identifier);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("correlation MDC contains an invalid domain identifier", invalid);
        }
    }

    @Override
    public TaskExecutionScope open(TaskExecutionContext context) {
        Objects.requireNonNull(context, "context");
        String previous = MDC.get(CorrelationContext.MDC_KEY);
        Optional<DomainIdentifier> correlation = context.correlationId();
        if (correlation.isPresent()) {
            MDC.put(CorrelationContext.MDC_KEY, correlation.orElseThrow().toString());
        } else {
            MDC.remove(CorrelationContext.MDC_KEY);
        }

        Span span = null;
        try {
            Span.Builder builder = tracer.spanBuilder()
                    .name(WORKER_SPAN_NAME)
                    .kind(Span.Kind.CONSUMER)
                    .tag(TASK_TYPE_TAG, context.taskType().value());
            correlation.ifPresent(identifier -> builder.tag(CORRELATION_TAG, identifier.toString()));
            span = builder.start();
            Tracer.SpanInScope traceScope = tracer.withSpan(span);
            Span createdSpan = span;
            return () -> {
                try {
                    traceScope.close();
                } finally {
                    try {
                        createdSpan.end();
                    } finally {
                        restore(previous);
                    }
                }
            };
        } catch (RuntimeException failure) {
            if (span != null) {
                span.end();
            }
            restore(previous);
            throw failure;
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationContext.MDC_KEY);
        } else {
            MDC.put(CorrelationContext.MDC_KEY, previous);
        }
    }
}
