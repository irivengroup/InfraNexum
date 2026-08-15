package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityContext;
import io.infranexum.rsot.domain.AuthorityMatrixEntry;
import io.infranexum.rsot.domain.CanonicalLifecycle;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.CanonicalObjectStatus;
import io.infranexum.rsot.domain.ContextRelationship;
import io.infranexum.rsot.ports.RsotRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC read repository for the isolated PGM-06-E01 RSOT foundation schema. */
public final class JdbcRsotRepository implements RsotRepository {
    private static final String CANONICAL_COLUMNS = "id,object_type,version,organization_id,schema_version,"
            + "status,status_reason,effective_from,effective_until,archived_at,archived_by,created_at,updated_at";

    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcRsotRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<CanonicalObject> findCanonicalObject(DomainIdentifier canonicalId) {
        Objects.requireNonNull(canonicalId, "canonicalId");
        String sql = "SELECT " + CANONICAL_COLUMNS + " FROM " + table("canonical_object") + " WHERE id=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, canonicalId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readCanonicalObject(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw fail("find RSOT canonical object", failure);
        }
    }

    @Override
    public List<CanonicalObject> listCanonicalObjects(int offset, int limit) {
        String pagination = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "LIMIT ? OFFSET ?" : "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        String sql = "SELECT " + CANONICAL_COLUMNS + " FROM " + table("canonical_object")
                + " ORDER BY updated_at DESC,id " + pagination;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
                statement.setInt(1, limit);
                statement.setInt(2, offset);
            } else {
                statement.setInt(1, offset);
                statement.setInt(2, limit);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CanonicalObject> result = new ArrayList<>();
                while (resultSet.next()) result.add(readCanonicalObject(resultSet));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw fail("list RSOT canonical objects", failure);
        }
    }

    @Override
    public List<CanonicalObject> listCanonicalObjects(DomainIdentifier organizationId, int offset, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        String pagination = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "LIMIT ? OFFSET ?" : "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        String sql = "SELECT " + CANONICAL_COLUMNS + " FROM " + table("canonical_object")
                + " WHERE organization_id=? ORDER BY updated_at DESC,id " + pagination;
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
                List<CanonicalObject> result = new ArrayList<>();
                while (resultSet.next()) result.add(readCanonicalObject(resultSet));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw fail("list RSOT canonical objects by organization", failure);
        }
    }

    @Override
    public List<AttributeAuthorityPolicy> authorityPolicies() {
        String sql = "SELECT id,object_type,attribute_path,authority_context,source_priority,effective_from,"
                + "effective_until,policy_version,approval_ref FROM " + table("attribute_authority_policy")
                + " ORDER BY object_type,attribute_path,effective_from,id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            List<AttributeAuthorityPolicy> result = new ArrayList<>();
            while (resultSet.next()) result.add(readAuthorityPolicy(resultSet));
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw fail("list RSOT authority policies", failure);
        }
    }

    @Override
    public List<AuthorityMatrixEntry> authorityMatrix() {
        String sql = "SELECT position_no,information_text,authority_name,rsot_contribution,conflict_strategy,matrix_version"
                + " FROM " + table("authority_matrix") + " ORDER BY position_no";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            List<AuthorityMatrixEntry> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(new AuthorityMatrixEntry(
                        resultSet.getInt("position_no"),
                        resultSet.getString("information_text"),
                        resultSet.getString("authority_name"),
                        resultSet.getString("rsot_contribution"),
                        resultSet.getString("conflict_strategy"),
                        resultSet.getString("matrix_version")));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw fail("list RSOT authority matrix", failure);
        }
    }

    @Override
    public List<ContextRelationship> contextMap() {
        String sql = "SELECT position_no,provider_name,contribution,direct_storage_write_allowed FROM "
                + table("context_relationship") + " ORDER BY position_no";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            List<ContextRelationship> result = new ArrayList<>();
            while (resultSet.next()) {
                boolean directWrite = dialect == JdbcDatabaseDialect.POSTGRESQL
                        ? resultSet.getBoolean("direct_storage_write_allowed")
                        : resultSet.getInt("direct_storage_write_allowed") == 1;
                result.add(new ContextRelationship(
                        resultSet.getInt("position_no"),
                        resultSet.getString("provider_name"),
                        resultSet.getString("contribution"),
                        directWrite));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw fail("list RSOT context map", failure);
        }
    }

    private CanonicalObject readCanonicalObject(ResultSet resultSet) throws SQLException {
        DomainIdentifier archivedBy = resultSet.getObject("archived_by") == null
                ? null : dialect.readIdentifier(resultSet, "archived_by");
        CanonicalLifecycle lifecycle = new CanonicalLifecycle(
                CanonicalObjectStatus.valueOf(resultSet.getString("status").toUpperCase(Locale.ROOT)),
                resultSet.getString("status_reason"),
                JdbcTemporal.readRequired(resultSet, "effective_from"),
                JdbcTemporal.readNullable(resultSet, "effective_until"),
                JdbcTemporal.readNullable(resultSet, "archived_at"),
                archivedBy);
        return new CanonicalObject(
                dialect.readIdentifier(resultSet, "id"),
                resultSet.getString("object_type"),
                resultSet.getLong("version"),
                dialect.readIdentifier(resultSet, "organization_id"),
                resultSet.getString("schema_version"),
                lifecycle,
                JdbcTemporal.readRequired(resultSet, "created_at"),
                JdbcTemporal.readRequired(resultSet, "updated_at"));
    }

    private AttributeAuthorityPolicy readAuthorityPolicy(ResultSet resultSet) throws SQLException {
        String priority = resultSet.getString("source_priority");
        List<AuthorityContext> sourcePriority = Arrays.stream(priority.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> AuthorityContext.valueOf(value.toUpperCase(Locale.ROOT)))
                .toList();
        return new AttributeAuthorityPolicy(
                dialect.readIdentifier(resultSet, "id"),
                resultSet.getString("object_type"),
                resultSet.getString("attribute_path"),
                AuthorityContext.valueOf(resultSet.getString("authority_context").toUpperCase(Locale.ROOT)),
                sourcePriority,
                JdbcTemporal.readRequired(resultSet, "effective_from"),
                JdbcTemporal.readNullable(resultSet, "effective_until"),
                resultSet.getString("policy_version"),
                resultSet.getString("approval_ref"));
    }

    private String table(String logicalName) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_rsot." + logicalName
                : "INFRANEXUM_RSOT_" + logicalName.toUpperCase(Locale.ROOT);
    }

    private static JdbcPersistenceException fail(String operation, SQLException failure) {
        return new JdbcPersistenceException(operation, failure);
    }
}
