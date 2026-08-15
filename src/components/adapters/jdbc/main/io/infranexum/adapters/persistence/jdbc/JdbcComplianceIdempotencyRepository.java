package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.ports.ComplianceIdempotencyRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Transaction-bound idempotency ledger for PGM-07-E03 contractual mutations. */
public final class JdbcComplianceIdempotencyRepository implements ComplianceIdempotencyRepository {
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcComplianceIdempotencyRepository(JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<Record> find(String key) {
        Objects.requireNonNull(key, "key");
        String sql = "SELECT idempotency_key,payload_sha256,operation_name,record_type,record_id,created_at FROM "
                + table() + " WHERE idempotency_key=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                return Optional.of(new Record(resultSet.getString("idempotency_key"), resultSet.getString("payload_sha256"),
                        resultSet.getString("operation_name"), resultSet.getString("record_type"),
                        dialect.readIdentifier(resultSet, "record_id"), JdbcTemporal.readRequired(resultSet, "created_at")));
            }
        } catch (SQLException failure) {
            throw fail("find ITAM compliance idempotency key", failure);
        }
    }

    @Override
    public void insert(Record record) {
        Objects.requireNonNull(record, "record");
        String sql = "INSERT INTO " + table()
                + " (idempotency_key,payload_sha256,operation_name,record_type,record_id,created_at) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, record.key()); statement.setString(2, record.payloadSha256());
            statement.setString(3, record.operation()); statement.setString(4, record.recordType());
            dialect.bindIdentifier(statement, 5, record.recordId()); JdbcTemporal.bindInstant(statement, 6, record.createdAt());
            if (statement.executeUpdate() != 1) throw new SQLException("compliance idempotency insert affected unexpected rows");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new ComplianceConflictException("IDEMPOTENCY_CONFLICT", "idempotency key was committed concurrently");
            }
            throw fail("insert ITAM compliance idempotency key", failure);
        }
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_itam.compliance_command_dedup" : "INX_ITAM_COMP_DEDUP";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
