package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.Subdivision;
import io.infranexum.organization.domain.SubdivisionCode;
import io.infranexum.organization.domain.SubdivisionState;
import io.infranexum.organization.domain.SubdivisionType;
import io.infranexum.organization.ports.SubdivisionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC subdivision repository enforcing organization scope on every key lookup. */
public final class JdbcSubdivisionRepository implements SubdivisionRepository {
    private static final String COLUMNS = "id,organization_id,code,display_name,description_text,"
            + "type_name,status,parent_subdivision_id,version,created_at,updated_at,deleted_at";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcSubdivisionRepository(
            DataSource dataSource,
            JdbcConnectionAccess transaction,
            JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long countByOrganization(DomainIdentifier organizationId) {
        String sql = "SELECT COUNT(*) FROM " + table()
                + " WHERE organization_id=? AND status <> 'DELETED'";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, Objects.requireNonNull(organizationId, "organizationId"));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("subdivision count returned no row");
                }
                return resultSet.getLong(1);
            }
        } catch (SQLException failure) {
            throw fail("count subdivisions", failure);
        }
    }

    @Override
    public boolean existsCode(DomainIdentifier organizationId, SubdivisionCode code) {
        String sql = "SELECT 1 FROM " + table() + " WHERE organization_id=? AND code=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, Objects.requireNonNull(organizationId, "organizationId"));
            statement.setString(2, Objects.requireNonNull(code, "code").value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException failure) {
            throw fail("check subdivision code", failure);
        }
    }

    @Override
    public Optional<Subdivision> findById(
            DomainIdentifier organizationId, DomainIdentifier id) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(id, "id");
        Connection current = currentConnectionOrNull();
        if (current != null) {
            return find(current, organizationId, id);
        }
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, organizationId, id);
        } catch (SQLException failure) {
            throw fail("find subdivision", failure);
        }
    }

    @Override
    public void insert(Subdivision subdivision) {
        Objects.requireNonNull(subdivision, "subdivision");
        String sql = "INSERT INTO " + table()
                + " (id,organization_id,code,display_name,description_text,type_name,status,"
                + "parent_subdivision_id,version,created_at,updated_at,deleted_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            bindInsert(statement, subdivision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("subdivision insert affected unexpected rows");
            }
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) {
                throw new OrganizationConflictException(
                        "SUBDIVISION_CODE_CONFLICT",
                        "subdivision code already exists in organization");
            }
            throw fail("insert subdivision", failure);
        }
    }

    @Override
    public void update(Subdivision subdivision, long expectedVersion) {
        Objects.requireNonNull(subdivision, "subdivision");
        String sql = "UPDATE " + table()
                + " SET display_name=?,description_text=?,type_name=?,status=?,parent_subdivision_id=?,"
                + "version=?,updated_at=?,deleted_at=? WHERE organization_id=? AND id=? AND version=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, subdivision.displayName());
            statement.setString(index++, subdivision.description());
            statement.setString(index++, subdivision.type().name());
            statement.setString(index++, subdivision.state().name());
            dialect.bindNullableIdentifier(statement, index++, subdivision.parentSubdivisionId());
            statement.setLong(index++, subdivision.version());
            JdbcTemporal.bindInstant(statement, index++, subdivision.updatedAt());
            JdbcTemporal.bindInstant(statement, index++, subdivision.deletedAt());
            dialect.bindIdentifier(statement, index++, subdivision.organizationId());
            dialect.bindIdentifier(statement, index++, subdivision.id());
            statement.setLong(index, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new OrganizationConflictException(
                        "VERSION_CONFLICT", "subdivision version changed");
            }
        } catch (SQLException failure) {
            throw fail("update subdivision", failure);
        }
    }

    @Override
    public List<Subdivision> list(
            DomainIdentifier organizationId, int offset, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        String pagination = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "LIMIT ? OFFSET ?"
                : "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        String sql = "SELECT " + COLUMNS + " FROM " + table()
                + " WHERE organization_id=? AND status <> 'DELETED'"
                + " ORDER BY display_name,code,id " + pagination;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, organizationId);
            if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
                statement.setInt(2, limit);
                statement.setInt(3, offset);
            } else {
                statement.setInt(2, offset);
                statement.setInt(3, limit);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Subdivision> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(read(resultSet));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw fail("list subdivisions", failure);
        }
    }

    private Optional<Subdivision> find(
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
            throw fail("find subdivision", failure);
        }
    }

    private Subdivision read(ResultSet resultSet) throws SQLException {
        return Subdivision.restore(
                dialect.readIdentifier(resultSet, "id"),
                dialect.readIdentifier(resultSet, "organization_id"),
                new SubdivisionCode(resultSet.getString("code")),
                resultSet.getString("display_name"),
                resultSet.getString("description_text"),
                SubdivisionType.valueOf(resultSet.getString("type_name")),
                SubdivisionState.valueOf(resultSet.getString("status")),
                nullableIdentifier(resultSet, "parent_subdivision_id"),
                resultSet.getLong("version"),
                JdbcTemporal.readRequired(resultSet, "created_at"),
                JdbcTemporal.readRequired(resultSet, "updated_at"),
                JdbcTemporal.readNullable(resultSet, "deleted_at"));
    }

    private void bindInsert(PreparedStatement statement, Subdivision subdivision) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, subdivision.id());
        dialect.bindIdentifier(statement, index++, subdivision.organizationId());
        statement.setString(index++, subdivision.code().value());
        statement.setString(index++, subdivision.displayName());
        statement.setString(index++, subdivision.description());
        statement.setString(index++, subdivision.type().name());
        statement.setString(index++, subdivision.state().name());
        dialect.bindNullableIdentifier(statement, index++, subdivision.parentSubdivisionId());
        statement.setLong(index++, subdivision.version());
        JdbcTemporal.bindInstant(statement, index++, subdivision.createdAt());
        JdbcTemporal.bindInstant(statement, index++, subdivision.updatedAt());
        JdbcTemporal.bindInstant(statement, index, subdivision.deletedAt());
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
                ? "infranexum_org.subdivision"
                : "INFRANEXUM_ORG_SUBDIVISION";
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
