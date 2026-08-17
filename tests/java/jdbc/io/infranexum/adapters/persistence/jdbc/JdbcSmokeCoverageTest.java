package io.infranexum.adapters.persistence.jdbc;

import org.junit.jupiter.api.Test;

/** Executes the deterministic JDBC smoke suites under Surefire so JaCoCo sees the same paths as offline validation. */
final class JdbcSmokeCoverageTest {
    @Test
    void transactionalEventStoreSmokeRunsUnderCoverage() throws Exception {
        JdbcAdapterSmoke.main(new String[0]);
    }

    @Test
    void auditJournalSmokeRunsUnderCoverage() throws Exception {
        JdbcAuditJournalSmoke.main(new String[0]);
    }

    @Test
    void taskStoreSmokeRunsUnderCoverage() throws Exception {
        JdbcTaskStoreSmoke.main(new String[0]);
    }
}
