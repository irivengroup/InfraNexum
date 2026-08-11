package io.infranexum.server.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.infranexum.adapters.persistence.jdbc.JdbcTaskStore;
import io.infranexum.core.contracts.RuntimeMode;
import io.infranexum.core.workers.InMemoryTaskStore;
import io.infranexum.core.workers.RetrySafety;
import io.infranexum.core.workers.TaskExecutionContext;
import io.infranexum.core.workers.TaskHandler;
import io.infranexum.core.workers.TaskScheduler;
import io.infranexum.core.workers.TaskStore;
import io.infranexum.core.workers.TaskType;
import io.infranexum.core.workers.TaskWorkerPool;
import io.infranexum.core.workers.WorkerPoolState;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import io.infranexum.server.persistence.JdbcIsolation;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class WorkerRuntimeConfigurationTest {
    private final WorkerRuntimeConfiguration configuration = new WorkerRuntimeConfiguration();
    private final DataSource dataSource = new NoConnectionDataSource();

    @Test
    void createsMemoryAndDialectSpecificDurableStoresWithoutOpeningConnections() {
        assertInstanceOf(InMemoryTaskStore.class, configuration.memoryTaskStore());
        assertInstanceOf(JdbcTaskStore.class, configuration.postgresqlTaskStore(
                dataSource, persistence(PersistenceMode.POSTGRESQL)));
        assertInstanceOf(JdbcTaskStore.class, configuration.oracleTaskStore(
                dataSource, persistence(PersistenceMode.ORACLE)));
    }

    @Test
    void freezesDiscoveredHandlersAndBuildsScheduler() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("testHandler", new CountingHandler());
        var registry = configuration.taskHandlerRegistry(beans.getBeanProvider(TaskHandler.class));
        Clock clock = configuration.workerClock();
        var identifiers = configuration.workerIdentifiers(clock);
        TaskScheduler scheduler = configuration.workerTaskScheduler(
                new InMemoryTaskStore(), registry, identifiers, clock);

        assertEquals(1, registry.size());
        assertNotNull(scheduler);
    }

    @Test
    void workerPoolUsesSpringCompatibleStartAndBoundedCloseLifecycle() {
        WorkerRuntimeProperties properties = WorkerRuntimePropertiesTest.valid();
        Clock clock = configuration.workerClock();
        var registry = new io.infranexum.core.workers.TaskHandlerRegistry(List.of());
        TaskStore store = new InMemoryTaskStore();
        TaskWorkerPool pool = configuration.taskWorkerPool(
                store,
                registry,
                configuration.workerRetryPolicy(properties),
                clock,
                server(),
                properties);

        assertEquals(WorkerPoolState.NEW, pool.state());
        pool.start();
        assertEquals(WorkerPoolState.RUNNING, pool.state());
        pool.close();
        assertEquals(WorkerPoolState.TERMINATED, pool.state());
    }

    private static PersistenceRuntimeProperties persistence(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }

    private static ServerRuntimeProperties server() {
        return new ServerRuntimeProperties(
                "server-worker-test",
                RuntimeMode.STANDALONE,
                "local",
                "local",
                "2.0.0-alpha.0.36",
                "2.0.0-draft.21");
    }

    private static final class CountingHandler implements TaskHandler {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public TaskType taskType() {
            return new TaskType("test.count");
        }

        @Override
        public RetrySafety retrySafety() {
            return RetrySafety.RETRY_SAFE;
        }

        @Override
        public void execute(TaskExecutionContext context) {
            executions.incrementAndGet();
        }
    }

    private static final class NoConnectionDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new AssertionError("bean construction must not open a JDBC connection");
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }
}
