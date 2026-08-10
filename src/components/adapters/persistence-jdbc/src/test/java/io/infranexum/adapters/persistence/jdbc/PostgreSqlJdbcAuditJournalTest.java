package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Live PostgreSQL tests for database-enforced append-only audit semantics. */
class PostgreSqlJdbcAuditJournalTest {
    private PGSimpleDataSource dataSource;
    private JdbcAuditJournal journal;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv("INFRANEXUM_POSTGRESQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL integration URL is not configured");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_USERNAME"));
        dataSource.setPassword(requiredEnvironment("INFRANEXUM_POSTGRESQL_TEST_PASSWORD"));
        journal = new JdbcAuditJournal(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        truncate();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource != null) truncate();
    }

    @Test
    void appendsReadsAndVerifiesCryptographicChain() {
        AuditScope scope = AuditScope.organization("org-live-audit");
        var first = journal.append(entry(1, scope));
        var second = journal.append(entry(2, scope));
        assertEquals(first.entryHash(), second.previousHash());
        assertEquals(List.of(1L, 2L), journal.readRange(scope, 1, 10, 10).stream().map(record -> record.sequence()).toList());
        var verification = journal.verify(scope);
        assertTrue(verification.valid());
        assertEquals(2, verification.verifiedRecords());
        assertEquals(second.entryHash(), verification.headHash());
    }

    @Test
    void concurrentWritersProduceOneContiguousChainWithoutDuplicateSequence() throws Exception {
        AuditScope scope = AuditScope.organization("org-live-concurrent");
        int writes = 32;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int index = 100; index < 100 + writes; index++) {
                int sequence = index;
                tasks.add(() -> journal.append(entry(sequence, scope)).sequence());
            }
            Set<Long> sequences = new HashSet<>();
            for (var future : executor.invokeAll(tasks)) assertTrue(sequences.add(future.get()));
            assertEquals(writes, sequences.size());
            assertEquals(1L, sequences.stream().mapToLong(Long::longValue).min().orElseThrow());
            assertEquals(writes, sequences.stream().mapToLong(Long::longValue).max().orElseThrow());
            assertTrue(journal.verify(scope).valid());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseTriggersRejectUpdateAndDeleteOfAuditEvidence() throws SQLException {
        AuditScope scope = AuditScope.organization("org-live-immutable");
        journal.append(entry(300, scope));
        assertThrows(SQLException.class, () -> executeMutation(
                "UPDATE infranexum_core.audit_entry SET result_name = 'FAILURE' WHERE scope_type = ? AND scope_id = ?", scope));
        assertThrows(SQLException.class, () -> executeMutation(
                "DELETE FROM infranexum_core.audit_entry WHERE scope_type = ? AND scope_id = ?", scope));
        assertEquals(1, count("infranexum_core.audit_entry"));
        assertTrue(journal.verify(scope).valid());
    }

    private void executeMutation(String sql, AuditScope scope) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.type());
            statement.setString(2, scope.id());
            statement.executeUpdate();
        }
    }

    private void truncate() throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                TRUNCATE TABLE infranexum_core.audit_entry,
                    infranexum_core.audit_chain_head,
                    infranexum_core.audit_purge_tombstone
                """)) {
            statement.executeUpdate();
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static AuditEntry entry(int value, AuditScope scope) {
        String suffix = "%012d".formatted(value);
        return new AuditEntry(
                DomainIdentifier.parse("018bcfe5-6800-7000-8000-" + suffix), scope,
                "user-live", "USER", "audit.live.write", "AUDIT_ENTRY", "entry-" + value,
                "ALLOW", Instant.parse("2026-08-10T09:30:00Z").plusMillis(value),
                DomainIdentifier.parse("018bcfe5-6800-7001-8000-" + suffix),
                "SUCCESS", "postgresql/integration", "approved", "192.0.2.30", "postgresql-test",
                Map.of("scenario", "postgresql-live"), "INTERNAL");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
