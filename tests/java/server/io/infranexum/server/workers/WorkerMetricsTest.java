package io.infranexum.server.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.infranexum.core.workers.TaskWorkerPool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class WorkerMetricsTest {
    @Test
    void exposesBoundedLowCardinalityPoolMetrics() throws Exception {
        TaskWorkerPool pool = WorkerHealthIndicatorTest.pool("metrics");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("taskWorkerPool", pool);
        WorkerMetrics metrics = new WorkerMetrics(
                WorkerHealthIndicatorTest.enabledOne(),
                beans.getBeanProvider(TaskWorkerPool.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertEquals(1.0d, gauge(registry, "infranexum.workers.enabled"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.ready"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.capacity"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.store.ready"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.store.ready.loops"));
        assertNotNull(registry.find("infranexum.workers.tasks.claimed").functionCounter());
        assertNotNull(registry.find("infranexum.workers.store.unavailable.failures").functionCounter());

        pool.start();
        awaitReadyMetric(registry, 5, TimeUnit.SECONDS);
        assertEquals(1.0d, gauge(registry, "infranexum.workers.ready"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.live"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.store.ready"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.store.ready.loops"));
        pool.close();
        assertEquals(0.0d, gauge(registry, "infranexum.workers.ready"));
        registry.close();
    }

    @Test
    void disabledRuntimeReportsConfigurationReadyAndZeroCapacity() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new WorkerMetrics(
                        WorkerHealthIndicatorTest.disabled(),
                        beans.getBeanProvider(TaskWorkerPool.class))
                .bindTo(registry);

        assertEquals(0.0d, gauge(registry, "infranexum.workers.enabled"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.ready"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.capacity"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.live"));
        assertEquals(1.0d, gauge(registry, "infranexum.workers.store.ready"));
        assertEquals(0.0d, gauge(registry, "infranexum.workers.store.ready.loops"));
        registry.close();
    }

    private static void awaitReadyMetric(
            SimpleMeterRegistry registry,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (gauge(registry, "infranexum.workers.ready") != 1.0d && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
