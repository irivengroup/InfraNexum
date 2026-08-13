package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalCredentialStateChangedException;
import io.infranexum.identity.local.ports.LocalIdentityRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC persistence for local human credentials and authentication security state. */
public final class JdbcLocalIdentityRepository implements LocalIdentityRepository {
    private static final String COLUMNS = "id,username,display_name,password_hash,must_change,status,failed_attempts,"
            + "locked_until,security_epoch,version,created_at,updated_at";
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcLocalIdentityRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public boolean hasAnyAccount() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table());
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("local account count returned no row");
            return rows.getLong(1) > 0;
        } catch (SQLException failure) {
            throw fail("count local accounts", failure);
        }
    }

    @Override
    public Optional<LocalAccount> findByUsername(String canonicalUsername) {
        return find("username = ?", canonicalUsername, null);
    }

    @Override
    public Optional<LocalAccount> findById(DomainIdentifier accountId) {
        return find("id = ?", null, Objects.requireNonNull(accountId, "accountId"));
    }

    @Override
    public void insert(LocalAccount account) {
        String sql = "INSERT INTO " + table() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            dialect.bindIdentifier(statement, i++, account.id());
            statement.setString(i++, account.username());
            statement.setString(i++, account.displayName());
            statement.setString(i++, account.passwordHash());
            bindBoolean(statement, i++, account.mustChange());
            statement.setString(i++, account.status().name());
            statement.setInt(i++, account.failedAttempts());
            JdbcTemporal.bindInstant(statement, i++, account.lockedUntil());
            statement.setLong(i++, account.securityEpoch());
            statement.setLong(i++, account.version());
            JdbcTemporal.bindInstant(statement, i++, account.createdAt());
            JdbcTemporal.bindInstant(statement, i, account.updatedAt());
            if (statement.executeUpdate() != 1) throw new SQLException("local account insert affected unexpected rows");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw new IllegalStateException("local account already exists");
            throw fail("insert local account", failure);
        }
    }

    @Override
    public LocalAccount recordFailedAuthentication(
            DomainIdentifier accountId, long expectedSecurityEpoch,
            int lockThreshold, Duration lockDuration, Instant now) {
        return mutateLocked(accountId, expectedSecurityEpoch, current -> {
            int attempts = current.failedAttempts() + 1;
            Instant lockedUntil = current.lockedUntil();
            if (attempts >= lockThreshold) {
                attempts = 0;
                lockedUntil = now.plus(lockDuration);
            }
            return new LocalAccount(current.id(), current.username(), current.displayName(), current.passwordHash(),
                    current.mustChange(), current.status(), attempts, lockedUntil, current.securityEpoch(),
                    current.version() + 1, current.createdAt(), now);
        });
    }

    @Override
    public LocalAccount recordSuccessfulAuthentication(
            DomainIdentifier accountId, long expectedSecurityEpoch, String replacementHash, Instant now) {
        return mutateLocked(accountId, expectedSecurityEpoch, current -> new LocalAccount(
                current.id(), current.username(), current.displayName(),
                replacementHash == null ? current.passwordHash() : replacementHash,
                current.mustChange(), current.status(), 0, null, current.securityEpoch(),
                current.version() + 1, current.createdAt(), now));
    }

    @Override
    public LocalAccount changePassword(
            DomainIdentifier accountId, long expectedSecurityEpoch,
            String passwordHash, boolean mustChange, Instant now) {
        Objects.requireNonNull(passwordHash, "passwordHash");
        return mutateLocked(accountId, expectedSecurityEpoch, current -> new LocalAccount(
                current.id(), current.username(), current.displayName(), passwordHash, mustChange,
                current.status(), 0, null, current.securityEpoch() + 1, current.version() + 1,
                current.createdAt(), now));
    }

    private Optional<LocalAccount> find(String clause, String username, DomainIdentifier id) {
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE " + clause;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (id != null) dialect.bindIdentifier(statement, 1, id); else statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("read local account", failure);
        }
    }

    private LocalAccount mutateLocked(
            DomainIdentifier accountId, long expectedSecurityEpoch,
            java.util.function.UnaryOperator<LocalAccount> mutation) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LocalAccount current;
                String select = "SELECT " + COLUMNS + " FROM " + table() + " WHERE id = ? FOR UPDATE";
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    dialect.bindIdentifier(statement, 1, accountId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("local account not found");
                        current = read(rows);
                    }
                }
                if (current.securityEpoch() != expectedSecurityEpoch) {
                    throw new LocalCredentialStateChangedException();
                }
                LocalAccount updated = mutation.apply(current);
                String sql = "UPDATE " + table() + " SET password_hash=?,must_change=?,status=?,failed_attempts=?,"
                        + "locked_until=?,security_epoch=?,version=?,updated_at=? WHERE id=? AND version=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int i = 1;
                    statement.setString(i++, updated.passwordHash());
                    bindBoolean(statement, i++, updated.mustChange());
                    statement.setString(i++, updated.status().name());
                    statement.setInt(i++, updated.failedAttempts());
                    JdbcTemporal.bindInstant(statement, i++, updated.lockedUntil());
                    statement.setLong(i++, updated.securityEpoch());
                    statement.setLong(i++, updated.version());
                    JdbcTemporal.bindInstant(statement, i++, updated.updatedAt());
                    dialect.bindIdentifier(statement, i++, updated.id());
                    statement.setLong(i, current.version());
                    if (statement.executeUpdate() != 1) throw new SQLException("local account concurrent update");
                }
                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
                return updated;
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("local account mutation failed", failure);
            }
        } catch (SQLException failure) {
            throw fail("mutate local account", failure);
        }
    }

    private LocalAccount read(ResultSet rows) throws SQLException {
        return new LocalAccount(
                dialect.readIdentifier(rows, "id"), rows.getString("username"), rows.getString("display_name"),
                rows.getString("password_hash"), readBoolean(rows, "must_change"),
                LocalAccountStatus.valueOf(rows.getString("status")), rows.getInt("failed_attempts"),
                JdbcTemporal.readNullable(rows, "locked_until"), rows.getLong("security_epoch"),
                rows.getLong("version"), JdbcTemporal.readRequired(rows, "created_at"), JdbcTemporal.readRequired(rows, "updated_at"));
    }

    private void bindBoolean(PreparedStatement statement, int index, boolean value) throws SQLException {
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) statement.setBoolean(index, value); else statement.setInt(index, value ? 1 : 0);
    }

    private boolean readBoolean(ResultSet rows, String column) throws SQLException {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? rows.getBoolean(column) : rows.getInt(column) == 1;
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_iam.local_account" : "INFRANEXUM_IAM_LOCAL_ACCOUNT";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
