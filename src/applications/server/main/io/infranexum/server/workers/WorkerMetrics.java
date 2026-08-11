package io.infranexum.server.workers;

import io.infranexum.core.workers.TaskWorkerPool;
import io.infranexum.core.workers.WorkerPoolSnapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/** Low-cardinality Micrometer metrics for the Server Workers runtime. */
public final class WorkerMetrics implements MeterBinder {
    private final WorkerRuntimeProperties properties;
    private final ObjectProvider<TaskWorkerPool> pools;

    public WorkerMetrics(
            WorkerRuntimeProperties properties,
            ObjectProvider<TaskWorkerPool> pools) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.pools = Objects.requireNonNull(pools, "pools");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Gauge.builder("infranexum.workers.enabled", this, WorkerMetrics::enabledValue)
                .description("Whether the InfraNexum Workers runtime is enabled")
                .register(registry);
        Gauge.builder("infranexum.workers.ready", this, WorkerMetrics::readyValue)
                .description("Whether all configured worker loops are alive and healthy")
                .register(registry);
        Gauge.builder("infranexum.workers.capacity", this, WorkerMetrics::capacityValue)
                .description("Configured bounded worker concurrency")
                .register(registry);
        Gauge.builder("infranexum.workers.live", this, WorkerMetrics::liveValue)
                .description("Currently alive worker loops")
                .register(registry);
        Gauge.builder("infranexum.workers.active", this, WorkerMetrics::activeValue)
                .description("Currently active task executions")
                .register(registry);
        functionCounter(registry, "infranexum.workers.tasks.claimed", WorkerPoolSnapshot::claimed);
        functionCounter(registry, "infranexum.workers.tasks.succeeded", WorkerPoolSnapshot::succeeded);
        functionCounter(registry, "infranexum.workers.tasks.retried", WorkerPoolSnapshot::retried);
        functionCounter(registry, "infranexum.workers.tasks.failed", WorkerPoolSnapshot::failed);
        functionCounter(registry, "infranexum.workers.tasks.cancelled", WorkerPoolSnapshot::cancelled);
        functionCounter(registry, "infranexum.workers.tasks.abandoned", WorkerPoolSnapshot::abandoned);
        FunctionCounter.builder(
                        "infranexum.workers.loop.failures",
                        this,
                        metrics -> metrics.snapshotValue(WorkerPoolSnapshot::fatalLoopFailures))
                .description("Cumulative fatal worker-loop failure count")
                .register(registry);
    }

    private void functionCounter(
            MeterRegistry registry,
            String name,
            java.util.function.ToDoubleFunction<WorkerPoolSnapshot> value) {
        FunctionCounter.builder(
                        name,
                        this,
                        metrics -> metrics.snapshotValue(value))
                .description("Cumulative InfraNexum worker task/runtime outcome count")
                .register(registry);
    }

    private double enabledValue() {
        return properties.enabled() ? 1.0d : 0.0d;
    }

    private double readyValue() {
        if (!properties.enabled()) {
            return 1.0d;
        }
        WorkerPoolSnapshot snapshot = snapshot();
        return snapshot != null && snapshot.ready() ? 1.0d : 0.0d;
    }

    private double capacityValue() {
        return properties.enabled() ? properties.concurrency() : 0.0d;
    }

    private double liveValue() {
        WorkerPoolSnapshot snapshot = snapshot();
        return snapshot == null ? 0.0d : snapshot.liveWorkers();
    }

    private double activeValue() {
        WorkerPoolSnapshot snapshot = snapshot();
        return snapshot == null ? 0.0d : snapshot.activeExecutions();
    }

    private double snapshotValue(java.util.function.ToDoubleFunction<WorkerPoolSnapshot> value) {
        WorkerPoolSnapshot snapshot = snapshot();
        return snapshot == null ? 0.0d : value.applyAsDouble(snapshot);
    }

    private WorkerPoolSnapshot snapshot() {
        TaskWorkerPool pool = pools.getIfUnique();
        return pool == null ? null : pool.snapshot();
    }
}
