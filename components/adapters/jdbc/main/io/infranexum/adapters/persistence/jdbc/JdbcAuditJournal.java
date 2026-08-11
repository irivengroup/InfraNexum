package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.audit.AuditCanonicalizer;
import io.infranexum.core.audit.AuditChainVerification;
import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditMetadataJson;
import io.infranexum.core.audit.AuditRecord;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** JDBC append-only audit journal with a serialized SHA-256 chain head per scope. */
public final class JdbcAuditJournal implements AuditJournal {
    private static final int MAX_READ = 10_000;
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;
    private final int transactionIsolation;

    public JdbcAuditJournal(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this(dataSource, dialect, Connection.TRANSACTION_READ_COMMITTED);
    }

    public JdbcAuditJournal(DataSource dataSource, JdbcDatabaseDialect dialect, int transactionIsolation) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (transactionIsolation == Connection.TRANSACTION_NONE) {
            throw new IllegalArgumentException("audit persistence requires transactional isolation");
        }
        this.transactionIsolation = transactionIsolation;
    }

    /** Appends one record while holding the scope chain-head row lock. */
    @Override
    public AuditRecord append(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        try (Connection connection = openConnection()) {
            try {
                ensureHead(connection, entry.scope());
                Head head = lockHead(connection, entry.scope());
                long sequence = Math.addExact(head.sequence(), 1L);
                String hash = AuditCanonicalizer.hash(sequence, head.hash(), entry);
                AuditRecord record = new AuditRecord(sequence, entry, head.hash(), hash);
                insertEntry(connection, record);
                updateHead(connection, entry.scope(), head, record);
                connection.commit();
                return record;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sql) throw new JdbcPersistenceException("append audit entry", sql);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("append audit entry", failure);
        }
    }

    /** Reads a bounded ordered scope range without allowing an unbounded table scan. */
    @Override
    public List<AuditRecord> readRange(AuditScope scope, long fromSequenceInclusive, long toSequenceInclusive, int limit) {
        Objects.requireNonNull(scope, "scope");
        validateRange(fromSequenceInclusive, toSequenceInclusive, limit);
        String sql = "SELECT " + columns() + " FROM " + entryTable()
                + " WHERE scope_type = ? AND scope_id = ? AND sequence_no BETWEEN ? AND ?"
                + " ORDER BY sequence_no";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.type());
            statement.setString(2, scope.id());
            statement.setLong(3, fromSequenceInclusive);
            statement.setLong(4, toSequenceInclusive);
            statement.setMaxRows(limit);
            statement.setFetchSize(Math.min(limit, 500));
            try (ResultSet rows = statement.executeQuery()) {
                List<AuditRecord> records = new ArrayList<>();
                while (rows.next()) records.add(readRecord(rows));
                return List.copyOf(records);
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("read audit range", failure);
        }
    }

    /** Recomputes the full per-scope chain using a streaming JDBC cursor. */
    @Override
    public AuditChainVerification verify(AuditScope scope) {
        Objects.requireNonNull(scope, "scope");
        String sql = "SELECT " + columns() + " FROM " + entryTable()
                + " WHERE scope_type = ? AND scope_id = ? ORDER BY sequence_no";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.type());
            statement.setString(2, scope.id());
            statement.setFetchSize(500);
            try (ResultSet rows = statement.executeQuery()) {
                String previous = AuditCanonicalizer.GENESIS_HASH;
                long expectedSequence = 1;
                long verified = 0;
                while (rows.next()) {
                    AuditRecord record = readRecord(rows);
                    String expectedHash = AuditCanonicalizer.hash(record.sequence(), previous, record.entry());
                    if (record.sequence() != expectedSequence
                            || !record.previousHash().equals(previous)
                            || !record.entryHash().equals(expectedHash)) {
                        return new AuditChainVerification(false, verified, record.sequence(), previous);
                    }
                    previous = record.entryHash();
                    verified++;
                    expectedSequence++;
                }
                return new AuditChainVerification(true, verified, 0, previous);
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("verify audit chain", failure);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
            if (connection.getTransactionIsolation() != transactionIsolation) connection.setTransactionIsolation(transactionIsolation);
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void ensureHead(Connection connection, AuditScope scope) throws SQLException {
        String sql = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "INSERT INTO infranexum_core.audit_chain_head (scope_type, scope_id, last_sequence, head_hash, updated_at) "
                    + "VALUES (?, ?, 0, ?, CURRENT_TIMESTAMP) ON CONFLICT (scope_type, scope_id) DO NOTHING"
                : "MERGE INTO INFRANEXUM_CORE_AUDIT_CHAIN_HEAD target USING (SELECT ? scope_type, ? scope_id FROM dual) source "
                    + "ON (target.scope_type = source.scope_type AND target.scope_id = source.scope_id) "
                    + "WHEN NOT MATCHED THEN INSERT (scope_type, scope_id, last_sequence, head_hash, updated_at) "
                    + "VALUES (source.scope_type, source.scope_id, 0, ?, SYSTIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.type());
            statement.setString(2, scope.id());
            statement.setString(3, AuditCanonicalizer.GENESIS_HASH);
            statement.executeUpdate();
        }
    }

    private Head lockHead(Connection connection, AuditScope scope) throws SQLException {
        String sql = "SELECT last_sequence, head_hash FROM " + headTable()
                + " WHERE scope_type = ? AND scope_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.type());
            statement.setString(2, scope.id());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("audit chain head was not created");
                return new Head(rows.getLong("last_sequence"), rows.getString("head_hash"));
            }
        }
    }

    private void insertEntry(Connection connection, AuditRecord record) throws SQLException {
        String metadata = AuditMetadataJson.encode(record.entry().metadata());
        String placeholder = dialect == JdbcDatabaseDialect.POSTGRESQL ? "CAST(? AS JSONB)" : "?";
        String sql = "INSERT INTO " + entryTable() + " (scope_type, scope_id, sequence_no, audit_id, actor_id, actor_type, action_name, "
                + "target_type, target_id, authorization_decision, occurred_at, correlation_id, result_name, origin_name, reason_text, client_ip, "
                + "user_agent, metadata_json, sensitivity, previous_hash, entry_hash, immutable_flag, created_at) VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + placeholder + ", ?, ?, ?, 'Y', CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            AuditEntry entry = record.entry();
            int index = 1;
            statement.setString(index++, entry.scope().type());
            statement.setString(index++, entry.scope().id());
            statement.setLong(index++, record.sequence());
            dialect.bindIdentifier(statement, index++, entry.auditId());
            statement.setString(index++, entry.actorId());
            statement.setString(index++, entry.actorType());
            statement.setString(index++, entry.action());
            statement.setString(index++, entry.targetType());
            statement.setString(index++, entry.targetId());
            statement.setString(index++, entry.authorizationDecision());
            JdbcTemporal.bindInstant(statement, index++, entry.timestamp());
            dialect.bindNullableIdentifier(statement, index++, entry.correlationId());
            statement.setString(index++, entry.result());
            statement.setString(index++, entry.origin());
            statement.setString(index++, entry.reason());
            statement.setString(index++, entry.clientIp());
            statement.setString(index++, entry.userAgent());
            statement.setString(index++, metadata);
            statement.setString(index++, entry.sensitivity());
            statement.setString(index++, record.previousHash());
            statement.setString(index, record.entryHash());
            if (statement.executeUpdate() != 1) throw new SQLException("audit append did not insert exactly one row");
        }
    }

    private void updateHead(Connection connection, AuditScope scope, Head previous, AuditRecord record) throws SQLException {
        String sql = "UPDATE " + headTable() + " SET last_sequence = ?, head_hash = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE scope_type = ? AND scope_id = ? AND last_sequence = ? AND head_hash = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, record.sequence());
            statement.setString(2, record.entryHash());
            statement.setString(3, scope.type());
            statement.setString(4, scope.id());
            statement.setLong(5, previous.sequence());
            statement.setString(6, previous.hash());
            if (statement.executeUpdate() != 1) throw new SQLException("audit chain head update lost serialization");
        }
    }

    private AuditRecord readRecord(ResultSet rows) throws SQLException {
        DomainIdentifier correlation = rows.getObject("correlation_id") == null ? null : dialect.readIdentifier(rows, "correlation_id");
        AuditScope scope = new AuditScope(rows.getString("scope_type"), rows.getString("scope_id"));
        AuditEntry entry = new AuditEntry(
                dialect.readIdentifier(rows, "audit_id"), scope,
                rows.getString("actor_id"), rows.getString("actor_type"), rows.getString("action_name"),
                rows.getString("target_type"), rows.getString("target_id"), rows.getString("authorization_decision"),
                JdbcTemporal.readRequired(rows, "occurred_at"), correlation, rows.getString("result_name"), rows.getString("origin_name"),
                rows.getString("reason_text"), rows.getString("client_ip"), rows.getString("user_agent"),
                AuditMetadataJson.decode(rows.getString("metadata_json")), rows.getString("sensitivity"));
        String immutable = rows.getString("immutable_flag");
        if (!"Y".equals(immutable)) throw new SQLException("persisted audit entry is not immutable");
        return new AuditRecord(rows.getLong("sequence_no"), entry, rows.getString("previous_hash"), rows.getString("entry_hash"));
    }

    private String headTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.audit_chain_head" : "INFRANEXUM_CORE_AUDIT_CHAIN_HEAD";
    }

    private String entryTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.audit_entry" : "INFRANEXUM_CORE_AUDIT_ENTRY";
    }

    private static String columns() {
        return "scope_type, scope_id, sequence_no, audit_id, actor_id, actor_type, action_name, target_type, target_id, "
                + "authorization_decision, occurred_at, correlation_id, result_name, origin_name, reason_text, client_ip, user_agent, "
                + "metadata_json, sensitivity, previous_hash, entry_hash, immutable_flag";
    }

    private static void validateRange(long from, long to, int limit) {
        if (from < 1 || to < from) throw new IllegalArgumentException("invalid audit sequence range");
        if (limit < 1 || limit > MAX_READ) throw new IllegalArgumentException("audit read limit must be between 1 and " + MAX_READ);
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    private record Head(long sequence, String hash) {}
}
