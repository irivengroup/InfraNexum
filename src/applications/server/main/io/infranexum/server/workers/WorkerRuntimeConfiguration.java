package io.infranexum.server.workers;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcTaskStore;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.workers.InMemoryTaskStore;
import io.infranexum.core.workers.TaskHandler;
import io.infranexum.core.workers.TaskHandlerRegistry;
import io.infranexum.core.workers.TaskScheduler;
import io.infranexum.core.workers.TaskStore;
import io.infranexum.core.workers.TaskWorkerPool;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition root for durable background tasks.
 *
 * <p>The runtime is started and closed by the Spring bean lifecycle. Persistence selection is
 * explicit and follows the already validated Server persistence mode; no database fallback is
 * permitted. Task handlers are discovered from bounded contexts and frozen into an immutable
 * registry when the application context is created.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "infranexum.workers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "infranexum.persistence.mode",
            havingValue = "MEMORY")
    TaskStore memoryTaskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnProperty(
            name = "infranexum.persistence.mode",
            havingValue = "POSTGRESQL",
            matchIfMissing = true)
    TaskStore postgresqlTaskStore(
            DataSource dataSource, PersistenceRuntimeProperties persistence) {
        return new JdbcTaskStore(
                dataSource,
                JdbcDatabaseDialect.POSTGRESQL,
                persistence.isolation().jdbcValue());
    }

    @Bean
    @ConditionalOnProperty(
            name = "infranexum.persistence.mode",
            havingValue = "ORACLE")
    TaskStore oracleTaskStore(
            DataSource dataSource, PersistenceRuntimeProperties persistence) {
        return new JdbcTaskStore(
                dataSource,
                JdbcDatabaseDialect.ORACLE,
                persistence.isolation().jdbcValue());
    }

    @Bean("workerClock")
    Clock workerClock() {
        return Clock.systemUTC();
    }

    @Bean("workerIdentifiers")
    UuidV7Generator workerIdentifiers(@Qualifier("workerClock") Clock clock) {
        return new UuidV7Generator(clock, new java.security.SecureRandom());
    }

    @Bean
    TaskHandlerRegistry taskHandlerRegistry(ObjectProvider<TaskHandler> handlers) {
        return new TaskHandlerRegistry(handlers.orderedStream().toList());
    }

    @Bean("workerRetryPolicy")
    RetryPolicy workerRetryPolicy(WorkerRuntimeProperties properties) {
        return properties.retryPolicy();
    }

    @Bean
    TaskScheduler workerTaskScheduler(
            TaskStore store,
            TaskHandlerRegistry registry,
            @Qualifier("workerIdentifiers") UuidV7Generator identifiers,
            @Qualifier("workerClock") Clock clock) {
        return new TaskScheduler(store, registry, identifiers, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    TaskWorkerPool taskWorkerPool(
            TaskStore store,
            TaskHandlerRegistry registry,
            @Qualifier("workerRetryPolicy") RetryPolicy retryPolicy,
            @Qualifier("workerClock") Clock clock,
            ServerRuntimeProperties server,
            WorkerRuntimeProperties properties) {
        return new TaskWorkerPool(
                store,
                registry,
                retryPolicy,
                clock,
                server.instanceId(),
                properties.poolConfiguration());
    }
}
