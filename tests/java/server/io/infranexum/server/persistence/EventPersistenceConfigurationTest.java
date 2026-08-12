package io.infranexum.server.persistence;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.RuntimeMode;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class EventPersistenceConfigurationTest {
    private final EventPersistenceConfiguration configuration = new EventPersistenceConfiguration();
    private final DataSource dataSource = new DataSource() {
        @Override
        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("connection is not required during bean construction");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new java.sql.SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    };

    @Test
    void createsExplicitMemoryStoreOnlyForLocalStandalone() {
        assertInstanceOf(UnavailableDataSource.class, configuration.memoryDataSource());
        assertInstanceOf(InMemoryEventStore.class, configuration.memoryEventStore(server(
                RuntimeMode.STANDALONE, "local", "local")));
        assertThrows(ConfigurationException.class, () -> configuration.memoryEventStore(server(
                RuntimeMode.REGIONAL, "eu-west", "paris")));
    }

    @Test
    void createsPostgresqlAndOracleStoresWithoutOpeningAConnection() {
        var properties = new PersistenceRuntimeProperties(
                PersistenceMode.POSTGRESQL, JdbcIsolation.READ_COMMITTED);
        assertInstanceOf(JdbcTransactionalEventStore.class,
                configuration.postgresqlEventStore(dataSource, properties));
        assertInstanceOf(JdbcTransactionalEventStore.class,
                configuration.oracleEventStore(dataSource, properties));
    }

    private static ServerRuntimeProperties server(RuntimeMode mode, String region, String site) {
        return new ServerRuntimeProperties(
                "server-test", mode, region, site, "2.0.0-alpha.0.49", "2.0.0-draft.21");
    }
}
