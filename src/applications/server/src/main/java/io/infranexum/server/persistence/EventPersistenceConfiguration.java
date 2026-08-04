package io.infranexum.server.persistence;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.RuntimeMode;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects exactly one event persistence implementation without a silent fallback. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceRuntimeProperties.class)
public class EventPersistenceConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "infranexum.persistence.mode",
            havingValue = "MEMORY",
            matchIfMissing = true)
    DataSource memoryDataSource() {
        return new UnavailableDataSource(
                "JDBC access is unavailable because infranexum.persistence.mode=MEMORY");
    }

    @Bean
    @ConditionalOnProperty(
            name = "infranexum.persistence.mode",
            havingValue = "MEMORY",
            matchIfMissing = true)
    TransactionalEventStore memoryEventStore(ServerRuntimeProperties server) {
        if (server.mode() != RuntimeMode.STANDALONE
                || !"local".equalsIgnoreCase(server.region())
                || !"local".equalsIgnoreCase(server.site())) {
            throw new ConfigurationException(
                    "MEMORY persistence is restricted to STANDALONE region=local site=local");
        }
        return new InMemoryEventStore();
    }

    @Bean
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    JdbcTransactionalEventStore postgresqlEventStore(
            DataSource dataSource, PersistenceRuntimeProperties properties) {
        return jdbcStore(dataSource, properties, JdbcDatabaseDialect.POSTGRESQL);
    }

    @Bean
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    JdbcTransactionalEventStore oracleEventStore(
            DataSource dataSource, PersistenceRuntimeProperties properties) {
        return jdbcStore(dataSource, properties, JdbcDatabaseDialect.ORACLE);
    }

    private static JdbcTransactionalEventStore jdbcStore(
            DataSource dataSource,
            PersistenceRuntimeProperties properties,
            JdbcDatabaseDialect dialect) {
        return new JdbcTransactionalEventStore(
                dataSource, dialect, properties.isolation().jdbcValue());
    }
}
