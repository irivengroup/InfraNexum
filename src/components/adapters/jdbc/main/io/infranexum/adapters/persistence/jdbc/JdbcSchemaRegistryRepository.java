package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.compatibility.RegisteredSchema;
import io.infranexum.core.compatibility.RegistryStatus;
import io.infranexum.core.compatibility.SchemaKind;
import io.infranexum.core.compatibility.SchemaProfile;
import io.infranexum.core.compatibility.SchemaProfileMember;
import io.infranexum.core.compatibility.SchemaRegistryException;
import io.infranexum.core.compatibility.SchemaRegistryRepository;
import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
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

/** JDBC adapter for the shared Core Contracts/Compatibility schema registry. */
public final class JdbcSchemaRegistryRepository implements SchemaRegistryRepository {
    private static final String SCHEMA_COLUMNS = "id,schema_key,schema_kind,owner_code,schema_version,status,definition_json,"
            + "checksum_sha256,revision,effective_at,created_at,updated_at,published_at,deprecated_at,sunset_at,"
            + "deprecation_reason,compatibility_evidence,breaking_approval_ref";
    private static final String PROFILE_COLUMNS = "id,profile_code,owner_code,profile_version,status,checksum_sha256,revision,"
            + "created_at,updated_at,published_at,deprecated_at,sunset_at,deprecation_reason";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcSchemaRegistryRepository(
            DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<RegisteredSchema> findSchema(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        return read(connection -> findSchema(connection, "id=?", statement -> dialect.bindIdentifier(statement, 1, id)));
    }

    @Override
    public Optional<RegisteredSchema> findSchemaVersion(String schemaKey, String version) {
        String key = normalize(schemaKey, "schemaKey");
        String semanticVersion = normalize(version, "version");
        return read(connection -> findSchema(connection, "schema_key=? AND schema_version=?", statement -> {
            statement.setString(1, key);
            statement.setString(2, semanticVersion);
        }));
    }

    @Override
    public Optional<RegisteredSchema> latestPublishedSchema(String schemaKey) {
        String key = normalize(schemaKey, "schemaKey");
        String pagination = dialect == JdbcDatabaseDialect.POSTGRESQL ? " LIMIT 1" : " FETCH FIRST 1 ROWS ONLY";
        String sql = "SELECT " + SCHEMA_COLUMNS + " FROM " + schemaTable()
                + " WHERE schema_key=? AND status IN ('PUBLISHED','DEPRECATED') ORDER BY published_at DESC,id" + pagination;
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readSchema(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<RegisteredSchema> listSchemas(
            String schemaKey, SchemaKind kind, RegistryStatus status, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SCHEMA_COLUMNS).append(" FROM ").append(schemaTable()).append(" WHERE 1=1");
        List<String> values = new ArrayList<>();
        if (schemaKey != null && !schemaKey.isBlank()) {
            sql.append(" AND schema_key=?"); values.add(schemaKey.strip().toLowerCase(Locale.ROOT));
        }
        if (kind != null) { sql.append(" AND schema_kind=?"); values.add(kind.name()); }
        if (status != null) { sql.append(" AND status=?"); values.add(status.name()); }
        sql.append(" ORDER BY schema_key,published_at DESC NULLS LAST,created_at DESC,id ");
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) sql.append("LIMIT ? OFFSET ?");
        else sql.append("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (String value : values) statement.setString(index++, value);
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) { statement.setInt(index++, limit); statement.setInt(index, offset); }
                else { statement.setInt(index++, offset); statement.setInt(index, limit); }
                try (ResultSet result = statement.executeQuery()) {
                    List<RegisteredSchema> schemas = new ArrayList<>();
                    while (result.next()) schemas.add(readSchema(result));
                    return List.copyOf(schemas);
                }
            }
        });
    }

    @Override
    public void insertSchema(RegisteredSchema schema) {
        Objects.requireNonNull(schema, "schema");
        String sql = "INSERT INTO " + schemaTable() + " (" + SCHEMA_COLUMNS + ") VALUES (?,?,?,?,?,?," + dialect.jsonParameter()
                + ",?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            bindSchema(statement, schema);
            requireSingle(statement.executeUpdate(), "schema insert");
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("SCHEMA_VERSION_CONFLICT", "schema key and version already exist");
            throw fail("insert schema registry entry", failure);
        }
    }

    @Override public void updateDraftSchema(RegisteredSchema schema) { updateSchema(schema, "DRAFT", "update draft schema"); }
    @Override public void publishSchema(RegisteredSchema schema) { updateSchema(schema, "DRAFT", "publish schema"); }
    @Override public void deprecateSchema(RegisteredSchema schema) { updateSchema(schema, "PUBLISHED", "deprecate schema"); }

    private void updateSchema(RegisteredSchema schema, String requiredPriorStatus, String operation) {
        Objects.requireNonNull(schema, "schema");
        String sql = "UPDATE " + schemaTable() + " SET status=?,definition_json=" + dialect.jsonParameter()
                + ",checksum_sha256=?,revision=?,effective_at=?,updated_at=?,published_at=?,deprecated_at=?,sunset_at=?,"
                + "deprecation_reason=?,compatibility_evidence=?,breaking_approval_ref=? WHERE id=? AND status=? AND revision=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, schema.status().name());
            dialect.bindJson(statement, index++, schema.definitionJson());
            statement.setString(index++, schema.checksumSha256());
            statement.setLong(index++, schema.revision());
            JdbcTemporal.bindInstant(statement, index++, schema.effectiveAt());
            JdbcTemporal.bindInstant(statement, index++, schema.updatedAt());
            JdbcTemporal.bindInstant(statement, index++, schema.publishedAt());
            JdbcTemporal.bindInstant(statement, index++, schema.deprecatedAt());
            JdbcTemporal.bindInstant(statement, index++, schema.sunsetAt());
            statement.setString(index++, schema.deprecationReason());
            statement.setString(index++, schema.compatibilityEvidence());
            statement.setString(index++, schema.breakingApprovalReference());
            dialect.bindIdentifier(statement, index++, schema.id());
            statement.setString(index++, requiredPriorStatus);
            statement.setLong(index, schema.revision() - 1);
            if (statement.executeUpdate() != 1) throw conflict("SCHEMA_REVISION_CONFLICT", "schema revision or lifecycle changed");
        } catch (SQLException failure) {
            throw fail(operation, failure);
        }
    }

    @Override
    public Optional<SchemaProfile> findProfile(DomainIdentifier id) {
        Objects.requireNonNull(id, "id");
        return read(connection -> findProfile(connection, "id=?", statement -> dialect.bindIdentifier(statement, 1, id)));
    }

    @Override
    public Optional<SchemaProfile> findProfileVersion(String code, String version) {
        String normalizedCode = normalize(code, "code");
        String semanticVersion = normalize(version, "version");
        return read(connection -> findProfile(connection, "profile_code=? AND profile_version=?", statement -> {
            statement.setString(1, normalizedCode);
            statement.setString(2, semanticVersion);
        }));
    }

    @Override
    public List<SchemaProfile> listProfiles(String code, RegistryStatus status, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(PROFILE_COLUMNS).append(" FROM ").append(profileTable()).append(" WHERE 1=1");
        List<String> values = new ArrayList<>();
        if (code != null && !code.isBlank()) { sql.append(" AND profile_code=?"); values.add(code.strip().toLowerCase(Locale.ROOT)); }
        if (status != null) { sql.append(" AND status=?"); values.add(status.name()); }
        sql.append(" ORDER BY profile_code,published_at DESC NULLS LAST,created_at DESC,id ");
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) sql.append("LIMIT ? OFFSET ?");
        else sql.append("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (String value : values) statement.setString(index++, value);
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) { statement.setInt(index++, limit); statement.setInt(index, offset); }
                else { statement.setInt(index++, offset); statement.setInt(index, limit); }
                try (ResultSet result = statement.executeQuery()) {
                    List<SchemaProfile> profiles = new ArrayList<>();
                    while (result.next()) profiles.add(readProfile(connection, result));
                    return List.copyOf(profiles);
                }
            }
        });
    }

    @Override
    public void insertProfile(SchemaProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Connection connection = transaction.requireCurrentConnection();
        String sql = "INSERT INTO " + profileTable() + " (" + PROFILE_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindProfile(statement, profile);
            requireSingle(statement.executeUpdate(), "profile insert");
            insertMembers(connection, profile);
        } catch (SQLException failure) {
            if (dialect.isUniqueViolation(failure)) throw conflict("SCHEMA_PROFILE_VERSION_CONFLICT", "profile code and version already exist");
            throw fail("insert schema profile", failure);
        }
    }

    @Override public void publishProfile(SchemaProfile profile) { updateProfile(profile, "DRAFT", "publish schema profile"); }
    @Override public void deprecateProfile(SchemaProfile profile) { updateProfile(profile, "PUBLISHED", "deprecate schema profile"); }

    private void updateProfile(SchemaProfile profile, String requiredPriorStatus, String operation) {
        Objects.requireNonNull(profile, "profile");
        String sql = "UPDATE " + profileTable() + " SET status=?,revision=?,updated_at=?,published_at=?,deprecated_at=?,sunset_at=?,"
                + "deprecation_reason=? WHERE id=? AND status=? AND revision=?";
        try (PreparedStatement statement = transaction.requireCurrentConnection().prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, profile.status().name());
            statement.setLong(index++, profile.revision());
            JdbcTemporal.bindInstant(statement, index++, profile.updatedAt());
            JdbcTemporal.bindInstant(statement, index++, profile.publishedAt());
            JdbcTemporal.bindInstant(statement, index++, profile.deprecatedAt());
            JdbcTemporal.bindInstant(statement, index++, profile.sunsetAt());
            statement.setString(index++, profile.deprecationReason());
            dialect.bindIdentifier(statement, index++, profile.id());
            statement.setString(index++, requiredPriorStatus);
            statement.setLong(index, profile.revision() - 1);
            if (statement.executeUpdate() != 1) throw conflict("SCHEMA_REVISION_CONFLICT", "profile revision or lifecycle changed");
        } catch (SQLException failure) {
            throw fail(operation, failure);
        }
    }

    private Optional<RegisteredSchema> findSchema(Connection connection, String predicate, SqlBinder binder) throws SQLException {
        String sql = "SELECT " + SCHEMA_COLUMNS + " FROM " + schemaTable() + " WHERE " + predicate;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSchema(result)) : Optional.empty();
            }
        }
    }

    private Optional<SchemaProfile> findProfile(Connection connection, String predicate, SqlBinder binder) throws SQLException {
        String sql = "SELECT " + PROFILE_COLUMNS + " FROM " + profileTable() + " WHERE " + predicate;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProfile(connection, result)) : Optional.empty();
            }
        }
    }

    private RegisteredSchema readSchema(ResultSet result) throws SQLException {
        return new RegisteredSchema(
                dialect.readIdentifier(result, "id"), result.getString("schema_key"), SchemaKind.valueOf(result.getString("schema_kind")),
                result.getString("owner_code"), ContractVersion.parse(result.getString("schema_version")), RegistryStatus.valueOf(result.getString("status")),
                result.getString("definition_json"), result.getString("checksum_sha256"), result.getLong("revision"),
                JdbcTemporal.readRequired(result, "effective_at"), JdbcTemporal.readRequired(result, "created_at"), JdbcTemporal.readRequired(result, "updated_at"),
                JdbcTemporal.readNullable(result, "published_at"), JdbcTemporal.readNullable(result, "deprecated_at"), JdbcTemporal.readNullable(result, "sunset_at"),
                result.getString("deprecation_reason"), result.getString("compatibility_evidence"), result.getString("breaking_approval_ref"));
    }

    private SchemaProfile readProfile(Connection connection, ResultSet result) throws SQLException {
        DomainIdentifier id = dialect.readIdentifier(result, "id");
        return new SchemaProfile(id, result.getString("profile_code"), result.getString("owner_code"),
                ContractVersion.parse(result.getString("profile_version")), RegistryStatus.valueOf(result.getString("status")),
                readMembers(connection, id), result.getString("checksum_sha256"), result.getLong("revision"),
                JdbcTemporal.readRequired(result, "created_at"), JdbcTemporal.readRequired(result, "updated_at"),
                JdbcTemporal.readNullable(result, "published_at"), JdbcTemporal.readNullable(result, "deprecated_at"),
                JdbcTemporal.readNullable(result, "sunset_at"), result.getString("deprecation_reason"));
    }

    private List<SchemaProfileMember> readMembers(Connection connection, DomainIdentifier profileId) throws SQLException {
        String sql = "SELECT position_no,schema_id,required_member FROM " + memberTable() + " WHERE profile_id=? ORDER BY position_no";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                List<SchemaProfileMember> members = new ArrayList<>();
                while (result.next()) {
                    boolean required = dialect == JdbcDatabaseDialect.POSTGRESQL ? result.getBoolean("required_member") : result.getInt("required_member") == 1;
                    members.add(new SchemaProfileMember(result.getInt("position_no"), dialect.readIdentifier(result, "schema_id"), required));
                }
                return List.copyOf(members);
            }
        }
    }

    private void bindSchema(PreparedStatement statement, RegisteredSchema schema) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, schema.id());
        statement.setString(index++, schema.schemaKey()); statement.setString(index++, schema.kind().name()); statement.setString(index++, schema.owner());
        statement.setString(index++, schema.version().toString()); statement.setString(index++, schema.status().name()); dialect.bindJson(statement, index++, schema.definitionJson());
        statement.setString(index++, schema.checksumSha256()); statement.setLong(index++, schema.revision()); JdbcTemporal.bindInstant(statement, index++, schema.effectiveAt());
        JdbcTemporal.bindInstant(statement, index++, schema.createdAt()); JdbcTemporal.bindInstant(statement, index++, schema.updatedAt()); JdbcTemporal.bindInstant(statement, index++, schema.publishedAt());
        JdbcTemporal.bindInstant(statement, index++, schema.deprecatedAt()); JdbcTemporal.bindInstant(statement, index++, schema.sunsetAt()); statement.setString(index++, schema.deprecationReason());
        statement.setString(index++, schema.compatibilityEvidence()); statement.setString(index, schema.breakingApprovalReference());
    }

    private void bindProfile(PreparedStatement statement, SchemaProfile profile) throws SQLException {
        int index = 1;
        dialect.bindIdentifier(statement, index++, profile.id()); statement.setString(index++, profile.code()); statement.setString(index++, profile.owner());
        statement.setString(index++, profile.version().toString()); statement.setString(index++, profile.status().name()); statement.setString(index++, profile.checksumSha256());
        statement.setLong(index++, profile.revision()); JdbcTemporal.bindInstant(statement, index++, profile.createdAt()); JdbcTemporal.bindInstant(statement, index++, profile.updatedAt());
        JdbcTemporal.bindInstant(statement, index++, profile.publishedAt()); JdbcTemporal.bindInstant(statement, index++, profile.deprecatedAt()); JdbcTemporal.bindInstant(statement, index++, profile.sunsetAt());
        statement.setString(index, profile.deprecationReason());
    }

    private void insertMembers(Connection connection, SchemaProfile profile) throws SQLException {
        String sql = "INSERT INTO " + memberTable() + " (profile_id,position_no,schema_id,required_member) VALUES (?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SchemaProfileMember member : profile.members()) {
                dialect.bindIdentifier(statement, 1, profile.id()); statement.setInt(2, member.position()); dialect.bindIdentifier(statement, 3, member.schemaId());
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) statement.setBoolean(4, member.required()); else statement.setInt(4, member.required() ? 1 : 0);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            for (int count : counts) if (count != 1 && count != java.sql.Statement.SUCCESS_NO_INFO) throw new SQLException("profile member insert failed");
        }
    }

    private <T> T read(SqlReader<T> reader) {
        Connection active = currentConnectionOrNull();
        try {
            if (active != null) return reader.read(active);
            try (Connection connection = dataSource.getConnection()) { return reader.read(connection); }
        } catch (SQLException failure) { throw fail("read schema registry", failure); }
    }

    private Connection currentConnectionOrNull() {
        try { return transaction.requireCurrentConnection(); }
        catch (IllegalStateException noTransaction) { return null; }
    }

    private String schemaTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.schema_registry_entry" : "INFRANEXUM_CORE_SCHEMA_REGISTRY"; }
    private String profileTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.schema_profile" : "INFRANEXUM_CORE_SCHEMA_PROFILE"; }
    private String memberTable() { return dialect == JdbcDatabaseDialect.POSTGRESQL ? "infranexum_core.schema_profile_member" : "INFRANEXUM_CORE_SCHEMA_PROFILE_MEMBER"; }

    private static String normalize(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
    private static void requireSingle(int count, String operation) throws SQLException { if (count != 1) throw new SQLException(operation + " affected " + count + " rows"); }
    private static SchemaRegistryException conflict(String code, String message) { return new SchemaRegistryException(code, message); }
    private static JdbcPersistenceException fail(String operation, SQLException failure) { return new JdbcPersistenceException(operation, failure); }

    @FunctionalInterface private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface SqlReader<T> { T read(Connection connection) throws SQLException; }
}
