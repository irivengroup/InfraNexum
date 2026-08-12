package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.ScopeType;
import io.infranexum.organization.domain.TemporalScope;
import io.infranexum.organization.ports.TemporalScopeRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC effective-dated governance-scope repository. */
public final class JdbcTemporalScopeRepository implements TemporalScopeRepository {
    private static final String COLUMNS =
            "id,organization_id,subdivision_id,scope_type,valid_from,valid_to,version,created_at";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcTemporalScopeRepository(
            DataSource dataSource,
            JdbcConnectionAccess transaction,
            JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void insert(TemporalScope scope) {
        Objects.requireNonNull(scope, "scope");
        String sql = "INSERT INTO " + table()
                + " (id,organization_id,subdivision_id,scope_type,valid_from,valid_to,version,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            dialect.bindIdentifier(statement, index++, scope.id());
            dialect.bindIdentifier(statement, index++, scope.organizationId());
            dialect.bindNullableIdentifier(statement, index++, scope.subdivisionId());
            statement.setString(index++, scope.type().name());
            JdbcTemporal.bindInstant(statement, index++, scope.validFrom());
            JdbcTemporal.bindInstant(statement, index++, scope.validTo());
            statement.setLong(index++, scope.version());
            JdbcTemporal.bindInstant(statement, index, scope.createdAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("scope insert affected unexpected rows");
            }
        } catch (SQLException failure) {
            throw fail("insert organization scope", failure);
        }
    }

    @Override
    public Optional<TemporalScope> findById(
            DomainIdentifier organizationId, DomainIdentifier id) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(id, "id");
        Connection current = currentConnectionOrNull();
        if (current != null) {
            return findById(current, organizationId, id);
        }
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, organizationId, id);
        } catch (SQLException failure) {
            throw fail("find organization scope", failure);
        }
    }

    @Override
    public List<TemporalScope> effective(DomainIdentifier organizationId, Instant at) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(at, "at");
        String sql = "SELECT " + COLUMNS + " FROM " + table()
                + " WHERE organization_id=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)"
                + " ORDER BY scope_type,valid_from,id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, organizationId);
            JdbcTemporal.bindInstant(statement, 2, at);
            JdbcTemporal.bindInstant(statement, 3, at);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TemporalScope> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(read(resultSet));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw fail("read effective organization scopes", failure);
        }
    }

    private Optional<TemporalScope> findById(
            Connection connection, DomainIdentifier organizationId, DomainIdentifier id) {
        String sql = "SELECT " + COLUMNS + " FROM " + table()
                + " WHERE organization_id=? AND id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, organizationId);
            dialect.bindIdentifier(statement, 2, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find organization scope", failure);
        }
    }

    private TemporalScope read(ResultSet resultSet) throws SQLException {
        return new TemporalScope(
                dialect.readIdentifier(resultSet, "id"),
                dialect.readIdentifier(resultSet, "organization_id"),
                nullableIdentifier(resultSet, "subdivision_id"),
                ScopeType.valueOf(resultSet.getString("scope_type")),
                JdbcTemporal.readRequired(resultSet, "valid_from"),
                JdbcTemporal.readNullable(resultSet, "valid_to"),
                resultSet.getLong("version"),
                JdbcTemporal.readRequired(resultSet, "created_at"));
    }

    private DomainIdentifier nullableIdentifier(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column) == null ? null : dialect.readIdentifier(resultSet, column);
    }

    private Connection currentConnectionOrNull() {
        try {
            return transaction.requireCurrentConnection();
        } catch (IllegalStateException noTransaction) {
            return null;
        }
    }

    private String table() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_org.temporal_scope"
                : "INFRANEXUM_ORG_TEMP_SCOPE";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
