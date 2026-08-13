package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.LocalSession;
import io.infranexum.identity.local.ports.LocalSessionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Durable local session repository storing only token fingerprints. */
public final class JdbcLocalSessionRepository implements LocalSessionRepository {
    private static final String COLUMNS = "id,account_id,token_hash,csrf_hash,security_epoch,created_at,last_seen_at,"
            + "idle_expires_at,absolute_expires_at,revoked_at";
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcLocalSessionRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void insert(LocalSession session) {
        String sql = "INSERT INTO " + table() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            dialect.bindIdentifier(statement, i++, session.id());
            dialect.bindIdentifier(statement, i++, session.accountId());
            statement.setString(i++, session.tokenHash());
            statement.setString(i++, session.csrfHash());
            statement.setLong(i++, session.securityEpoch());
            JdbcTemporal.bindInstant(statement, i++, session.createdAt());
            JdbcTemporal.bindInstant(statement, i++, session.lastSeenAt());
            JdbcTemporal.bindInstant(statement, i++, session.idleExpiresAt());
            JdbcTemporal.bindInstant(statement, i++, session.absoluteExpiresAt());
            JdbcTemporal.bindInstant(statement, i, session.revokedAt());
            if (statement.executeUpdate() != 1) throw new SQLException("local session insert affected unexpected rows");
        } catch (SQLException failure) {
            throw fail("insert local session", failure);
        }
    }

    @Override
    public Optional<LocalSession> findByTokenHash(String tokenHash) {
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE token_hash = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find local session", failure);
        }
    }

    @Override
    public void touch(DomainIdentifier sessionId, Instant lastSeenAt, Instant idleExpiresAt) {
        update("last_seen_at=?,idle_expires_at=?", sessionId, statement -> {
            JdbcTemporal.bindInstant(statement, 1, lastSeenAt);
            JdbcTemporal.bindInstant(statement, 2, idleExpiresAt);
            return 3;
        });
    }

    @Override
    public void revoke(DomainIdentifier sessionId, Instant revokedAt) {
        update("revoked_at=?", sessionId, statement -> {
            JdbcTemporal.bindInstant(statement, 1, revokedAt);
            return 2;
        });
    }

    @Override
    public void revokeAllForAccount(DomainIdentifier accountId, Instant revokedAt) {
        String sql = "UPDATE " + table() + " SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcTemporal.bindInstant(statement, 1, revokedAt);
            dialect.bindIdentifier(statement, 2, accountId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw fail("revoke local sessions", failure);
        }
    }

    private void update(String assignments, DomainIdentifier id, Binder binder) {
        String sql = "UPDATE " + table() + " SET " + assignments + " WHERE id=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int idIndex = binder.bind(statement);
            dialect.bindIdentifier(statement, idIndex, id);
            if (statement.executeUpdate() != 1) throw new SQLException("local session update affected unexpected rows");
        } catch (SQLException failure) {
            throw fail("update local session", failure);
        }
    }

    private LocalSession read(ResultSet rows) throws SQLException {
        return new LocalSession(
                dialect.readIdentifier(rows, "id"), dialect.readIdentifier(rows, "account_id"),
                rows.getString("token_hash"), rows.getString("csrf_hash"), rows.getLong("security_epoch"),
                JdbcTemporal.readRequired(rows, "created_at"), JdbcTemporal.readRequired(rows, "last_seen_at"),
                JdbcTemporal.readRequired(rows, "idle_expires_at"), JdbcTemporal.readRequired(rows, "absolute_expires_at"),
                JdbcTemporal.readNullable(rows, "revoked_at"));
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.local_session" : "INFRANEXUM_IAM_LOCAL_SESSION";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }

    @FunctionalInterface
    private interface Binder { int bind(PreparedStatement statement) throws SQLException; }
}
