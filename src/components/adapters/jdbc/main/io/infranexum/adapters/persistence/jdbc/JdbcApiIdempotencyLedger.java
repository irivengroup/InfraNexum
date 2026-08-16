package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.IdempotencyLedger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** PostgreSQL/Oracle durable implementation of the platform HTTP idempotency ledger. */
public final class JdbcApiIdempotencyLedger implements IdempotencyLedger {
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcApiIdempotencyLedger(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<Entry> find(String scopeKey, String operation, String key) {
        String sql = "SELECT scope_key,operation_name,idempotency_key,request_sha256,state,http_status,content_type,etag,location,response_body_b64,created_at,updated_at FROM "
                + table() + " WHERE scope_key=? AND operation_name=? AND idempotency_key=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeKey); statement.setString(2, operation); statement.setString(3, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                int status = result.getInt("http_status");
                Integer nullableStatus = result.wasNull() ? null : status;
                return Optional.of(new Entry(
                        result.getString("scope_key"), result.getString("operation_name"), result.getString("idempotency_key"),
                        result.getString("request_sha256"), State.valueOf(result.getString("state")), nullableStatus,
                        result.getString("content_type"), result.getString("etag"), result.getString("location"),
                        result.getString("response_body_b64"), JdbcTemporal.readRequired(result, "created_at"),
                        JdbcTemporal.readRequired(result, "updated_at")));
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("read API idempotency ledger", failure);
        }
    }

    @Override
    public boolean reserve(String scopeKey, String operation, String key, String requestSha256, Instant now) {
        String sql = "INSERT INTO " + table()
                + " (scope_key,operation_name,idempotency_key,request_sha256,state,http_status,content_type,etag,location,response_body_b64,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, scopeKey); statement.setString(2, operation); statement.setString(3, key);
                statement.setString(4, requestSha256); statement.setString(5, State.IN_PROGRESS.name());
                statement.setNull(6, java.sql.Types.INTEGER); statement.setNull(7, java.sql.Types.VARCHAR);
                statement.setNull(8, java.sql.Types.VARCHAR); statement.setNull(9, java.sql.Types.VARCHAR);
                statement.setString(10, null); JdbcTemporal.bindInstant(statement, 11, now); JdbcTemporal.bindInstant(statement, 12, now);
                statement.executeUpdate(); connection.commit(); return true;
            } catch (SQLException failure) {
                rollback(connection);
                if (dialect.isUniqueViolation(failure)) return false;
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("reserve API idempotency key", failure);
        }
    }

    @Override
    public void complete(String scopeKey, String operation, String key, String requestSha256, int httpStatus,
            String contentType, String etag, String location, String responseBodyBase64, Instant now) {
        String sql = "UPDATE " + table()
                + " SET state=?,http_status=?,content_type=?,etag=?,location=?,response_body_b64=?,updated_at=?"
                + " WHERE scope_key=? AND operation_name=? AND idempotency_key=? AND request_sha256=? AND state=?";
        update(sql, statement -> {
            statement.setString(1, State.COMPLETED.name()); statement.setInt(2, httpStatus);
            nullable(statement, 3, contentType); nullable(statement, 4, etag); nullable(statement, 5, location);
            nullableClob(statement, 6, responseBodyBase64); JdbcTemporal.bindInstant(statement, 7, now);
            statement.setString(8, scopeKey); statement.setString(9, operation); statement.setString(10, key);
            statement.setString(11, requestSha256); statement.setString(12, State.IN_PROGRESS.name());
        }, "complete API idempotency key", true);
    }

    @Override
    public void markIndeterminate(String scopeKey, String operation, String key, String requestSha256, Instant now) {
        String sql = "UPDATE " + table() + " SET state=?,updated_at=? WHERE scope_key=? AND operation_name=? AND idempotency_key=? AND request_sha256=? AND state=?";
        update(sql, statement -> {
            statement.setString(1, State.INDETERMINATE.name()); JdbcTemporal.bindInstant(statement, 2, now);
            statement.setString(3, scopeKey); statement.setString(4, operation); statement.setString(5, key);
            statement.setString(6, requestSha256); statement.setString(7, State.IN_PROGRESS.name());
        }, "mark API idempotency key indeterminate", false);
    }

    @Override
    public void release(String scopeKey, String operation, String key, String requestSha256) {
        String sql = "DELETE FROM " + table() + " WHERE scope_key=? AND operation_name=? AND idempotency_key=? AND request_sha256=? AND state=?";
        update(sql, statement -> {
            statement.setString(1, scopeKey); statement.setString(2, operation); statement.setString(3, key);
            statement.setString(4, requestSha256); statement.setString(5, State.IN_PROGRESS.name());
        }, "release API idempotency key", false);
    }

    private void update(String sql, SqlBinder binder, String operation, boolean requireOne) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                int count = statement.executeUpdate();
                if (requireOne && count != 1) throw new SQLException("idempotency state transition affected " + count + " rows");
                connection.commit();
            } catch (SQLException failure) {
                rollback(connection); throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException(operation, failure);
        }
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.api_idempotency" : "INFRANEXUM_CORE_API_IDEMP";
    }

    private static void nullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR); else statement.setString(index, value);
    }

    private static void nullableClob(PreparedStatement statement, int index, String value) throws SQLException {
        statement.setString(index, value);
    }

    private static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { /* preserve original failure */ }
    }

    @FunctionalInterface private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }
}
