package io.infranexum.server.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.workers.InMemoryTaskStore;
import io.infranexum.core.workers.TaskHandlerRegistry;
import io.infranexum.core.workers.TaskWorkerPool;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class WorkerHealthIndicatorTest {
    private static final RetryPolicy RETRY = new RetryPolicy() {
        @Override public int maximumAttempts() { return 1; }
        @Override public Duration delayAfterFailure(int failedAttempt) { return Duration.ZERO; }
    };

    @Test
    void disabledWorkersAreReadyWithoutCreatingAPool() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        WorkerHealthIndicator indicator = new WorkerHealthIndicator(
                disabled(), beans.getBeanProvider(TaskWorkerPool.class));

        assertEquals("UP", indicator.health().getStatus().getCode());
        assertEquals(false, indicator.health().getDetails().get("enabled"));
    }

    @Test
    void enabledWorkersFailClosedUntilTheCompletePoolIsAlive() throws Exception {
        StaticListableBeanFactory missing = new StaticListableBeanFactory();
        WorkerHealthIndicator unavailable = new WorkerHealthIndicator(
                WorkerRuntimePropertiesTest.valid(), missing.getBeanProvider(TaskWorkerPool.class));
        assertEquals("DOWN", unavailable.health().getStatus().getCode());

        TaskWorkerPool pool = pool("health");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("taskWorkerPool", pool);
        WorkerHealthIndicator indicator = new WorkerHealthIndicator(
                enabledOne(), beans.getBeanProvider(TaskWorkerPool.class));
        assertEquals("DOWN", indicator.health().getStatus().getCode());

        pool.start();
        assertEquals("UP", awaitStatus(indicator, "UP", 5, TimeUnit.SECONDS));
        pool.close();
        assertEquals("DOWN", indicator.health().getStatus().getCode());
    }

    private static String awaitStatus(
            WorkerHealthIndicator indicator,
            String expected,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        String status = indicator.health().getStatus().getCode();
        while (!expected.equals(status) && System.nanoTime() < deadline) {
            Thread.sleep(5);
            status = indicator.health().getStatus().getCode();
        }
        return status;
    }

    static TaskWorkerPool pool(String runtimeId) {
        return new TaskWorkerPool(
                new InMemoryTaskStore(),
                new TaskHandlerRegistry(List.of()),
                RETRY,
                Clock.systemUTC(),
                runtimeId,
                enabledOne().poolConfiguration());
    }

    static WorkerRuntimeProperties enabledOne() {
        return new WorkerRuntimeProperties(
                true,
                1,
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                0.0d);
    }

    static WorkerRuntimeProperties disabled() {
        WorkerRuntimeProperties enabled = enabledOne();
        return new WorkerRuntimeProperties(
                false,
                enabled.concurrency(),
                enabled.pollInterval(),
                enabled.leaseDuration(),
                enabled.heartbeatInterval(),
                enabled.shutdownTimeout(),
                enabled.maximumAttempts(),
                enabled.initialRetryDelay(),
                enabled.maximumRetryDelay(),
                enabled.jitterRatio());
    }
}
