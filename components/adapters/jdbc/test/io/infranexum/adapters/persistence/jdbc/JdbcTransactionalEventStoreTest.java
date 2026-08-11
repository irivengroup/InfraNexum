package io.infranexum.adapters.persistence.jdbc;

import org.junit.jupiter.api.Test;

/** Executes the same dialect contract used by the dependency-free local smoke gate. */
class JdbcTransactionalEventStoreTest {
    @Test
    void postgresqlUnitOfWorkAndOutboxContract() throws Exception {
        JdbcAdapterSmoke.provesPostgreSqlUnitOfWorkAndOutbox();
    }

    @Test
    void oracleClaimAndInboxContract() {
        JdbcAdapterSmoke.provesOracleClaimsAndInboxDeduplication();
    }

    @Test
    void configurationAndOwnershipGuards() {
        JdbcAdapterSmoke.provesConfigurationAndOwnershipGuards();
    }
}
