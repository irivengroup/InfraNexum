package io.infranexum.server.workers;

import io.infranexum.core.workers.TaskWorkerPool;
import io.infranexum.core.workers.WorkerPoolSnapshot;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Readiness contribution for the bounded Workers runtime. */
public final class WorkerHealthIndicator implements HealthIndicator {
    private final WorkerRuntimeProperties properties;
    private final ObjectProvider<TaskWorkerPool> pools;

    public WorkerHealthIndicator(
            WorkerRuntimeProperties properties,
            ObjectProvider<TaskWorkerPool> pools) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.pools = Objects.requireNonNull(pools, "pools");
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        TaskWorkerPool pool = pools.getIfUnique();
        if (pool == null) {
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("reason", "worker pool unavailable or ambiguous")
                    .build();
        }
        WorkerPoolSnapshot snapshot = pool.snapshot();
        Health.Builder builder = snapshot.ready() ? Health.up() : Health.down();
        return builder.withDetail("enabled", true)
                .withDetail("state", snapshot.state().name())
                .withDetail("configuredConcurrency", snapshot.configuredConcurrency())
                .withDetail("liveWorkers", snapshot.liveWorkers())
                .withDetail("activeExecutions", snapshot.activeExecutions())
                .withDetail("fatalLoopFailures", snapshot.fatalLoopFailures())
                .build();
    }
}
