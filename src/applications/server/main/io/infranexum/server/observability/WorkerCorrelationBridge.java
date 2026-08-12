package io.infranexum.server.observability;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.workers.TaskCorrelationProvider;
import io.infranexum.core.workers.TaskExecutionContext;
import io.infranexum.core.workers.TaskExecutionScopeFactory;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Bridges validated HTTP correlation into durable task scheduling and restores it around handlers.
 *
 * <p>Only the canonical correlation identifier crosses the asynchronous boundary. Raw request
 * headers, security context and arbitrary MDC entries are deliberately not propagated.
 */
public final class WorkerCorrelationBridge implements TaskCorrelationProvider, TaskExecutionScopeFactory {
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
        String previous = MDC.get(CorrelationContext.MDC_KEY);
        Optional<DomainIdentifier> correlation = context.correlationId();
        if (correlation.isPresent()) {
            MDC.put(CorrelationContext.MDC_KEY, correlation.orElseThrow().toString());
        } else {
            MDC.remove(CorrelationContext.MDC_KEY);
        }
        return () -> restore(previous);
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationContext.MDC_KEY);
        } else {
            MDC.put(CorrelationContext.MDC_KEY, previous);
        }
    }
}
