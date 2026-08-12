package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.Organization;
import io.infranexum.organization.domain.OrganizationCode;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationState;
import io.infranexum.organization.ports.OrganizationRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC organization repository sharing business writes with the transactional outbox connection. */
public final class JdbcOrganizationRepository implements OrganizationRepository {
    private static final String COLUMNS = "id,code,display_name,legal_name,country_code,"
            + "default_language,timezone,currency,parent_organization_id,status,version,created_at,updated_at";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcOrganizationRepository(
            DataSource dataSource,
            JdbcConnectionAccess transaction,
            JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE status <> 'DELETED'";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("organization count returned no row");
            }
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw fail("count organizations", failure);
        }
    }

    @Override
    public boolean existsByCode(OrganizationCode code) {
        Objects.requireNonNull(code, "code");
        String sql = "SELECT 1 FROM " + table() + " WHERE code = ?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            statement.setString(1, code.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException failure) {
            throw fail("check organization uniqueness", failure);
        }
    }

    @Override
    public Optional<Organization> findById(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        Connection current = currentConnectionOrNull();
        if (current != null) {
            return findById(current, id);
        }
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, id);
        } catch (SQLException failure) {
            throw fail("find organization", failure);
        }
    }

    @Override
    public Optional<Organization> findByCode(OrganizationCode code) {
        Objects.requireNonNull(code, "code");
        Connection current = currentConnectionOrNull();
        if (current != null) {
            return findByCode(current, code);
        }
        try (Connection connection = dataSource.getConnection()) {
            return findByCode(connection, code);
        } catch (SQLException failure) {
            throw fail("find organization by code", failure);
        }
    }

    @Override
    public void insert(Organization organization) {
        Objects.requireNonNull(organization, "organization");
        String sql = "INSERT INTO " + table()
                + " (id,code,display_name,legal_name,country_code,default_language,timezone,currency,"
                + "parent_organization_id,status,version,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            bindInsert(statement, organization);
            requireSingleUpdate(statement.executeUpdate(), "organization insert");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new OrganizationConflictException(
                        "ORG_CODE_CONFLICT", "organization code already exists");
            }
            throw fail("insert organization", failure);
        }
    }

    @Override
    public void update(Organization organization, long expectedVersion) {
        Objects.requireNonNull(organization, "organization");
        String sql = "UPDATE " + table()
                + " SET display_name=?,legal_name=?,country_code=?,default_language=?,timezone=?,currency=?,"
                + "parent_organization_id=?,status=?,version=?,updated_at=? WHERE id=? AND version=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, organization.displayName());
            statement.setString(index++, organization.legalName());
            statement.setString(index++, organization.countryCode());
            statement.setString(index++, organization.defaultLanguage());
            statement.setString(index++, organization.timezone());
            statement.setString(index++, organization.currency());
            dialect.bindNullableIdentifier(statement, index++, organization.parentOrganizationId());
            statement.setString(index++, organization.state().name());
            statement.setLong(index++, organization.version());
            JdbcTemporal.bindInstant(statement, index++, organization.updatedAt());
            dialect.bindIdentifier(statement, index++, organization.id());
            statement.setLong(index, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new OrganizationConflictException(
                        "VERSION_CONFLICT", "organization version changed");
            }
        } catch (SQLException failure) {
            throw fail("update organization", failure);
        }
    }

    @Override
    public List<Organization> search(
            String query, OrganizationState state, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM ")
                .append(table())
                .append(" WHERE status <> 'DELETED'");
        List<String> parameters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (UPPER(code) LIKE ? OR UPPER(display_name) LIKE ? OR UPPER(legal_name) LIKE ?)");
            String normalized = "%" + query.strip().toUpperCase(Locale.ROOT) + "%";
            parameters.add(normalized);
            parameters.add(normalized);
            parameters.add(normalized);
        }
        if (state != null) {
            sql.append(" AND status = ?");
            parameters.add(state.name());
        }
        sql.append(" ORDER BY display_name, code, id ");
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
            sql.append("LIMIT ? OFFSET ?");
        } else {
            sql.append("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        }

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String parameter : parameters) {
                statement.setString(index++, parameter);
            }
            if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
            } else {
                statement.setInt(index++, offset);
                statement.setInt(index, limit);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Organization> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(read(resultSet));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw fail("search organizations", failure);
        }
    }

    private Optional<Organization> findById(Connection connection, DomainIdentifier id) {
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find organization", failure);
        }
    }

    private Optional<Organization> findByCode(Connection connection, OrganizationCode code) {
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find organization by code", failure);
        }
    }

    private Organization read(ResultSet resultSet) throws SQLException {
        return Organization.restore(
                dialect.readIdentifier(resultSet, "id"),
                new OrganizationCode(resultSet.getString("code")),
                resultSet.getString("display_name"),
                resultSet.getString("legal_name"),
                resultSet.getString("country_code"),
                resultSet.getString("default_language"),
                resultSet.getString("timezone"),
                resultSet.getString("currency"),
                nullableIdentifier(resultSet, "parent_organization_id"),
                OrganizationState.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                JdbcTemporal.readRequired(resultSet, "created_at"),
                JdbcTemporal.readRequired(resultSet, "updated_at"));
    }

    private void bindInsert(PreparedStatement statement, Organization organization) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, organization.id());
        statement.setString(index++, organization.code().value());
        statement.setString(index++, organization.displayName());
        statement.setString(index++, organization.legalName());
        statement.setString(index++, organization.countryCode());
        statement.setString(index++, organization.defaultLanguage());
        statement.setString(index++, organization.timezone());
        statement.setString(index++, organization.currency());
        dialect.bindNullableIdentifier(statement, index++, organization.parentOrganizationId());
        statement.setString(index++, organization.state().name());
        statement.setLong(index++, organization.version());
        JdbcTemporal.bindInstant(statement, index++, organization.createdAt());
        JdbcTemporal.bindInstant(statement, index, organization.updatedAt());
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
                ? "infranexum_org.organization"
                : "INFRANEXUM_ORG_ORGANIZATION";
    }

    private static void requireSingleUpdate(int count, String operation) throws SQLException {
        if (count != 1) {
            throw new SQLException(operation + " affected unexpected rows: " + count);
        }
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
