package io.infranexum.server.identityaccess;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import io.infranexum.server.persistence.JdbcIsolation;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.persistence.UnavailableDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Composition regression coverage for IAM weak references introduced by PGM-04-E02. */
class IdentityAccessRuntimeConfigurationWeakReferenceTest {
    private static final DataSource DATA_SOURCE = new UnavailableDataSource("test only");

    @Test
    void organizationScopePortUsesDurableJdbcForBothCertifiedDialects() {
        IdentityAccessRuntimeConfiguration configuration = new IdentityAccessRuntimeConfiguration();
        JdbcTransactionalEventStore postgresEvents =
                new JdbcTransactionalEventStore(DATA_SOURCE, JdbcDatabaseDialect.POSTGRESQL);
        OrganizationScopeReferencePort postgres = configuration.identityAccessOrganizationScopeReferences(
                DATA_SOURCE, postgresEvents, properties(PersistenceMode.POSTGRESQL));
        assertInstanceOf(OrganizationScopeReferenceAdapter.class, postgres);

        JdbcTransactionalEventStore oracleEvents =
                new JdbcTransactionalEventStore(DATA_SOURCE, JdbcDatabaseDialect.ORACLE);
        OrganizationScopeReferencePort oracle = configuration.identityAccessOrganizationScopeReferences(
                DATA_SOURCE, oracleEvents, properties(PersistenceMode.ORACLE));
        assertInstanceOf(OrganizationScopeReferenceAdapter.class, oracle);
    }

    @Test
    void organizationScopePortRejectsMemoryAndNonJdbcEventStores() {
        IdentityAccessRuntimeConfiguration configuration = new IdentityAccessRuntimeConfiguration();
        JdbcTransactionalEventStore jdbc =
                new JdbcTransactionalEventStore(DATA_SOURCE, JdbcDatabaseDialect.POSTGRESQL);
        assertThrows(IllegalStateException.class, () -> configuration.identityAccessOrganizationScopeReferences(
                DATA_SOURCE, new InMemoryEventStore(), properties(PersistenceMode.POSTGRESQL)));
        assertThrows(IllegalStateException.class, () -> configuration.identityAccessOrganizationScopeReferences(
                DATA_SOURCE, jdbc, properties(PersistenceMode.MEMORY)));
    }

    private static PersistenceRuntimeProperties properties(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }
}
