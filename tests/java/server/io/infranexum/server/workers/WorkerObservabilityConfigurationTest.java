package io.infranexum.server.workers;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.infranexum.core.workers.TaskWorkerPool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class WorkerObservabilityConfigurationTest {
    @Test
    void createsHealthAndMetricsEvenWhenTheRuntimePoolIsAbsent() {
        WorkerObservabilityConfiguration configuration = new WorkerObservabilityConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        WorkerRuntimeProperties properties = WorkerHealthIndicatorTest.disabled();

        assertNotNull(configuration.workersHealthIndicator(
                properties, beans.getBeanProvider(TaskWorkerPool.class)));
        assertNotNull(configuration.workerMetrics(
                properties, beans.getBeanProvider(TaskWorkerPool.class)));
    }
}
