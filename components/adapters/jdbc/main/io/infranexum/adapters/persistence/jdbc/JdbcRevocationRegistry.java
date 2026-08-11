package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.RevocationRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** Database-backed revocation view evaluated without network access. */
public final class JdbcRevocationRegistry implements RevocationRegistry {
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcRevocationRegistry(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public boolean isKeyRevoked(String keyId, Instant at) {
        return isRevoked("KEY", Objects.requireNonNull(keyId, "keyId"), at);
    }

    @Override
    public boolean isActivationRevoked(DomainIdentifier activationId, Instant at) {
        return isRevoked("ACTIVATION", Objects.requireNonNull(activationId, "activationId").toString(), at);
    }

    private boolean isRevoked(String type, String key, Instant at) {
        Objects.requireNonNull(at, "at");
        String table = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "core_activation_revocation"
                : "CORE_ACTIVATION_REVOCATION";
        String sql = "SELECT effective_at FROM " + table + " WHERE revocation_type=? AND revocation_key=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                Instant effectiveAt = JdbcTemporal.readRequired(result, "effective_at");
                return !at.isBefore(effectiveAt);
            }
        } catch (SQLException error) {
            throw new JdbcPersistenceException("load activation revocation", error);
        }
    }
}
