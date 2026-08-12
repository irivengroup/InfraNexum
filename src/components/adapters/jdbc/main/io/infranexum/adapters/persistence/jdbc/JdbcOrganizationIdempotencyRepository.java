package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.ports.IdempotencyRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Transaction-bound command deduplication for the Organization context. */
public final class JdbcOrganizationIdempotencyRepository implements IdempotencyRepository {
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcOrganizationIdempotencyRepository(
            JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<Record> find(String key) {
        Objects.requireNonNull(key, "key");
        String sql = "SELECT idempotency_key,payload_sha256,resource_type,resource_id,created_at"
                + " FROM " + table() + " WHERE idempotency_key=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Record(
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("payload_sha256"),
                        resultSet.getString("resource_type"),
                        dialect.readIdentifier(resultSet, "resource_id"),
                        JdbcTemporal.readRequired(resultSet, "created_at")));
            }
        } catch (SQLException failure) {
            throw fail("find organization idempotency key", failure);
        }
    }

    @Override
    public void insert(Record record) {
        Objects.requireNonNull(record, "record");
        String sql = "INSERT INTO " + table()
                + " (idempotency_key,payload_sha256,resource_type,resource_id,created_at)"
                + " VALUES (?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, record.key());
            statement.setString(2, record.payloadSha256());
            statement.setString(3, record.resourceType());
            dialect.bindIdentifier(statement, 4, record.resourceId());
            JdbcTemporal.bindInstant(statement, 5, record.createdAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("idempotency insert affected unexpected rows");
            }
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new OrganizationConflictException(
                        "IDEMPOTENCY_CONFLICT",
                        "idempotency key was committed concurrently");
            }
            throw fail("insert organization idempotency key", failure);
        }
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_org.command_dedup"
                : "INFRANEXUM_ORG_COMMAND_DEDUP";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
