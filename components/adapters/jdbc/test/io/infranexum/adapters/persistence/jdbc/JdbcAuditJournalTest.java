package io.infranexum.adapters.persistence.jdbc;

import org.junit.jupiter.api.Test;

/** Executes the dependency-free audit JDBC contract under Surefire and JaCoCo. */
class JdbcAuditJournalTest {
    @Test void postgresqlAppendReadAndVerification() { JdbcAuditJournalSmoke.provesPostgreSqlAppendReadAndVerification(); }
    @Test void oracleMappingAndFailureGuards() { JdbcAuditJournalSmoke.provesOracleMappingAndFailureGuards(); }
    @Test void tamperAndConfigurationGuards() { JdbcAuditJournalSmoke.provesTamperAndConfigurationGuards(); }
}
