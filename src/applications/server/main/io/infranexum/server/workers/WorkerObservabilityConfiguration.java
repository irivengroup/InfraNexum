package io.infranexum.server.workers;

import io.infranexum.core.workers.TaskWorkerPool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Always-on health and metric composition for the optionally enabled Workers runtime. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerRuntimeProperties.class)
public class WorkerObservabilityConfiguration {

    @Bean
    WorkerHealthIndicator workersHealthIndicator(
            WorkerRuntimeProperties properties,
            ObjectProvider<TaskWorkerPool> pools) {
        return new WorkerHealthIndicator(properties, pools);
    }

    @Bean
    WorkerMetrics workerMetrics(
            WorkerRuntimeProperties properties,
            ObjectProvider<TaskWorkerPool> pools) {
        return new WorkerMetrics(properties, pools);
    }
}
