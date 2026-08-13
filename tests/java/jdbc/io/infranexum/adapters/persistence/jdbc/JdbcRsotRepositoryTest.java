package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityContext;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.CanonicalObjectStatus;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Deterministic mapping and failure coverage for the isolated RSOT JDBC repository. */
class JdbcRsotRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-13T16:00:00Z");
    private static final DomainIdentifier CANONICAL = id(1);
    private static final DomainIdentifier ORGANIZATION = id(2);
    private static final DomainIdentifier ARCHIVER = id(3);

    @Test
    void constructorRejectsMissingDependenciesAndFindMapsPostgresqlRows() {
        ScriptedDataSource empty = dataSource(connection(query(List.of())));
        assertThrows(NullPointerException.class, () -> new JdbcRsotRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcRsotRepository(empty, null));

        JdbcRsotRepository absent = new JdbcRsotRepository(empty, JdbcDatabaseDialect.POSTGRESQL);
        assertTrue(absent.findCanonicalObject(CANONICAL).isEmpty());
        assertTrue(empty.sql.getFirst().contains("infranexum_rsot.canonical_object"));
        assertEquals(CANONICAL.value(), empty.parameters.getFirst().get(1));

        ScriptedDataSource source = dataSource(connection(query(List.of(canonicalRow(
                JdbcDatabaseDialect.POSTGRESQL, CanonicalObjectStatus.VALIDATED, null, null)))));
        CanonicalObject object = new JdbcRsotRepository(source, JdbcDatabaseDialect.POSTGRESQL)
                .findCanonicalObject(CANONICAL)
                .orElseThrow();
        assertEquals(CANONICAL, object.id());
        assertEquals(ORGANIZATION, object.organizationId());
        assertEquals("rsot.asset", object.objectType());
        assertEquals(1L, object.version());
        assertEquals("1.0.0", object.schemaVersion());
        assertEquals(CanonicalObjectStatus.VALIDATED, object.lifecycle().status());
        assertNull(object.lifecycle().archivedAt());
        assertNull(object.lifecycle().archivedBy());
    }

    @Test
    void oracleFindMapsArchivedIdentifiersAndListUsesDialectSpecificPagination() {
        Map<String, Object> archived = canonicalRow(
                JdbcDatabaseDialect.ORACLE,
                CanonicalObjectStatus.ARCHIVED,
                NOW.plusSeconds(30),
                ARCHIVER);
        ScriptedDataSource find = dataSource(connection(query(List.of(archived))));
        CanonicalObject object = new JdbcRsotRepository(find, JdbcDatabaseDialect.ORACLE)
                .findCanonicalObject(CANONICAL)
                .orElseThrow();
        assertEquals(CanonicalObjectStatus.ARCHIVED, object.lifecycle().status());
        assertEquals(ARCHIVER, object.lifecycle().archivedBy());
        assertEquals(NOW.plusSeconds(30), object.lifecycle().archivedAt());
        assertTrue(find.sql.getFirst().contains("INFRANEXUM_RSOT_CANONICAL_OBJECT"));
        assertEquals(CANONICAL.toString(), find.parameters.getFirst().get(1));

        Map<String, Object> validated = canonicalRow(
                JdbcDatabaseDialect.POSTGRESQL, CanonicalObjectStatus.VALIDATED, null, null);
        Map<String, Object> reconciled = canonicalRow(
                JdbcDatabaseDialect.POSTGRESQL, CanonicalObjectStatus.RECONCILED, null, null);
        reconciled.put("id", id(4).value());
        ScriptedDataSource pg = dataSource(connection(query(List.of(validated, reconciled))));
        List<CanonicalObject> pgObjects = new JdbcRsotRepository(pg, JdbcDatabaseDialect.POSTGRESQL)
                .listCanonicalObjects(5, 25);
        assertEquals(2, pgObjects.size());
        assertTrue(pg.sql.getFirst().contains("LIMIT ? OFFSET ?"));
        assertEquals(Map.of(1, 25, 2, 5), pg.parameters.getFirst());

        Map<String, Object> oracleRow = canonicalRow(
                JdbcDatabaseDialect.ORACLE, CanonicalObjectStatus.RECONCILED, null, null);
        ScriptedDataSource oracle = dataSource(connection(query(List.of(oracleRow))));
        assertEquals(1, new JdbcRsotRepository(oracle, JdbcDatabaseDialect.ORACLE)
                .listCanonicalObjects(7, 30).size());
        assertTrue(oracle.sql.getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        assertEquals(Map.of(1, 7, 2, 30), oracle.parameters.getFirst());
    }

    @Test
    void authorityPoliciesMapPriorityTemporalBoundsAndBothTableNames() {
        Map<String, Object> first = policyRow(JdbcDatabaseDialect.POSTGRESQL, id(10), "DCIM,RSOT", null);
        Map<String, Object> second = policyRow(
                JdbcDatabaseDialect.POSTGRESQL, id(11), "DDI, RSOT", NOW.plusSeconds(3600));
        second.put("authority_context", "ddi");
        second.put("object_type", "rsot.server");
        second.put("attribute_path", "network.*");
        ScriptedDataSource pg = dataSource(connection(query(List.of(first, second))));
        List<AttributeAuthorityPolicy> policies =
                new JdbcRsotRepository(pg, JdbcDatabaseDialect.POSTGRESQL).authorityPolicies();
        assertEquals(2, policies.size());
        assertEquals(List.of(AuthorityContext.DCIM, AuthorityContext.RSOT), policies.getFirst().sourcePriority());
        assertNull(policies.getFirst().effectiveUntil());
        assertEquals(AuthorityContext.DDI, policies.getLast().authorityContext());
        assertEquals(NOW.plusSeconds(3600), policies.getLast().effectiveUntil());
        assertTrue(pg.sql.getFirst().contains("infranexum_rsot.attribute_authority_policy"));

        Map<String, Object> oraclePolicy = policyRow(JdbcDatabaseDialect.ORACLE, id(12), "ITAM", null);
        oraclePolicy.put("authority_context", "itam");
        ScriptedDataSource oracle = dataSource(connection(query(List.of(oraclePolicy))));
        assertEquals(AuthorityContext.ITAM,
                new JdbcRsotRepository(oracle, JdbcDatabaseDialect.ORACLE)
                        .authorityPolicies().getFirst().authorityContext());
        assertTrue(oracle.sql.getFirst().contains("INFRANEXUM_RSOT_ATTRIBUTE_AUTHORITY_POLICY"));
    }

    @Test
    void matrixAndContextMapMapAllColumnsAndBooleanRepresentations() {
        ScriptedDataSource matrix = dataSource(connection(query(List.of(Map.of(
                "position_no", 1,
                "information_text", "Organisation, subdivision",
                "authority_name", "Organisation",
                "rsot_contribution", "reference",
                "conflict_strategy", "Organisation prevails",
                "matrix_version", "2.0.0-draft.21")))));
        var rows = new JdbcRsotRepository(matrix, JdbcDatabaseDialect.POSTGRESQL).authorityMatrix();
        assertEquals(1, rows.size());
        assertEquals("Organisation", rows.getFirst().authority());
        assertEquals("2.0.0-draft.21", rows.getFirst().matrixVersion());

        ScriptedDataSource pgContext = dataSource(connection(query(List.of(Map.of(
                "position_no", 1,
                "provider_name", "Organization",
                "contribution", "scope",
                "direct_storage_write_allowed", false)))));
        var pg = new JdbcRsotRepository(pgContext, JdbcDatabaseDialect.POSTGRESQL).contextMap();
        assertFalse(pg.getFirst().directStorageWriteAllowed());

        ScriptedDataSource oracleContext = dataSource(connection(query(List.of(Map.of(
                "position_no", 2,
                "provider_name", "IAM",
                "contribution", "actors",
                "direct_storage_write_allowed", 0)))));
        var oracle = new JdbcRsotRepository(oracleContext, JdbcDatabaseDialect.ORACLE).contextMap();
        assertFalse(oracle.getFirst().directStorageWriteAllowed());
        assertTrue(oracleContext.sql.getFirst().contains("INFRANEXUM_RSOT_CONTEXT_RELATIONSHIP"));
    }

    @Test
    void everyRepositoryOperationPreservesSqlFailureAsCause() {
        assertFailure("find RSOT canonical object", () -> new JdbcRsotRepository(
                dataSource(connection(failure(new SQLException("offline", "08006")))),
                JdbcDatabaseDialect.POSTGRESQL).findCanonicalObject(CANONICAL));
        assertFailure("list RSOT canonical objects", () -> new JdbcRsotRepository(
                dataSource(connection(failure(new SQLException("offline", "08006")))),
                JdbcDatabaseDialect.POSTGRESQL).listCanonicalObjects(0, 10));
        assertFailure("list RSOT authority policies", () -> new JdbcRsotRepository(
                dataSource(connection(failure(new SQLException("offline", "08006")))),
                JdbcDatabaseDialect.POSTGRESQL).authorityPolicies());
        assertFailure("list RSOT authority matrix", () -> new JdbcRsotRepository(
                dataSource(connection(failure(new SQLException("offline", "08006")))),
                JdbcDatabaseDialect.POSTGRESQL).authorityMatrix());
        assertFailure("list RSOT context map", () -> new JdbcRsotRepository(
                dataSource(connection(failure(new SQLException("offline", "08006")))),
                JdbcDatabaseDialect.POSTGRESQL).contextMap());
    }

    @Test
    void invalidPersistedDataFailsClosedInsteadOfBeingSilentlyCoerced() {
        Map<String, Object> invalidStatus = canonicalRow(
                JdbcDatabaseDialect.POSTGRESQL, CanonicalObjectStatus.VALIDATED, null, null);
        invalidStatus.put("status", "unknown");
        assertThrows(IllegalArgumentException.class, () -> new JdbcRsotRepository(
                dataSource(connection(query(List.of(invalidStatus)))), JdbcDatabaseDialect.POSTGRESQL)
                .findCanonicalObject(CANONICAL));

        Map<String, Object> invalidPriority = policyRow(
                JdbcDatabaseDialect.POSTGRESQL, id(20), "   ,  ", null);
        assertThrows(IllegalArgumentException.class, () -> new JdbcRsotRepository(
                dataSource(connection(query(List.of(invalidPriority)))), JdbcDatabaseDialect.POSTGRESQL)
                .authorityPolicies());

        Map<String, Object> forbiddenWrite = new HashMap<>();
        forbiddenWrite.put("position_no", 1);
        forbiddenWrite.put("provider_name", "IAM");
        forbiddenWrite.put("contribution", "actors");
        forbiddenWrite.put("direct_storage_write_allowed", true);
        assertThrows(IllegalArgumentException.class, () -> new JdbcRsotRepository(
                dataSource(connection(query(List.of(forbiddenWrite)))), JdbcDatabaseDialect.POSTGRESQL)
                .contextMap());
    }

    private static Map<String, Object> canonicalRow(
            JdbcDatabaseDialect dialect,
            CanonicalObjectStatus status,
            Instant archivedAt,
            DomainIdentifier archivedBy) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", jdbcId(dialect, CANONICAL));
        row.put("object_type", "rsot.asset");
        row.put("version", 1L);
        row.put("organization_id", jdbcId(dialect, ORGANIZATION));
        row.put("schema_version", "1.0.0");
        row.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        row.put("status_reason", status == CanonicalObjectStatus.ARCHIVED ? "retired" : null);
        row.put("effective_from", NOW);
        row.put("effective_until", null);
        row.put("archived_at", archivedAt);
        row.put("archived_by", archivedBy == null ? null : jdbcId(dialect, archivedBy));
        row.put("created_at", NOW);
        row.put("updated_at", archivedAt == null ? NOW : archivedAt);
        return row;
    }

    private static Map<String, Object> policyRow(
            JdbcDatabaseDialect dialect,
            DomainIdentifier policyId,
            String sourcePriority,
            Instant effectiveUntil) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", jdbcId(dialect, policyId));
        row.put("object_type", "rsot.asset");
        row.put("attribute_path", "location.site_id");
        row.put("authority_context", "dcim");
        row.put("source_priority", sourcePriority);
        row.put("effective_from", NOW.minusSeconds(60));
        row.put("effective_until", effectiveUntil);
        row.put("policy_version", "1.0.0");
        row.put("approval_ref", "GOV-APPROVAL-1");
        return row;
    }

    private static Object jdbcId(JdbcDatabaseDialect dialect, DomainIdentifier value) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? value.value() : value.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static DomainIdentifier id(int seed) {
        return DomainIdentifier.parse("019ffbda-2000-7000-8000-%012x".formatted(seed));
    }

    private static void assertFailure(String operation, Runnable invocation) {
        JdbcPersistenceException error = assertThrows(JdbcPersistenceException.class, invocation::run);
        assertEquals("JDBC persistence operation failed: " + operation, error.getMessage());
        SQLException cause = assertInstanceOf(SQLException.class, error.getCause());
        assertEquals("08006", cause.getSQLState());
    }

    private static ScriptedDataSource dataSource(ConnectionScript... scripts) {
        return new ScriptedDataSource(List.of(scripts));
    }

    private static ConnectionScript connection(Plan... plans) {
        return new ConnectionScript(List.of(plans));
    }

    private static Plan query(List<Map<String, Object>> rows) {
        return new Plan(rows, null);
    }

    private static Plan failure(SQLException failure) {
        return new Plan(List.of(), failure);
    }

    private record Plan(List<Map<String, Object>> rows, SQLException failure) {}

    private record ConnectionScript(List<Plan> plans) {}

    /** Small JDBC script engine: no live DB and no mocking dependency are needed. */
    private static final class ScriptedDataSource implements DataSource {
        private final Queue<ConnectionScript> scripts;
        private final List<String> sql = new ArrayList<>();
        private final List<Map<Integer, Object>> parameters = new ArrayList<>();

        private ScriptedDataSource(List<ConnectionScript> scripts) {
            this.scripts = new ArrayDeque<>(scripts);
        }

        @Override
        public Connection getConnection() throws SQLException {
            ConnectionScript script = scripts.poll();
            if (script == null) throw new SQLException("unexpected connection");
            Queue<Plan> plans = new ArrayDeque<>(script.plans());
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            sql.add((String) args[0]);
                            Plan plan = plans.poll();
                            if (plan == null) throw new SQLException("unexpected statement");
                            Map<Integer, Object> bound = new HashMap<>();
                            parameters.add(bound);
                            yield statement(plan, bound);
                        }
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement statement(Plan plan, Map<Integer, Object> bound) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setInt", "setLong", "setString", "setObject", "setBoolean", "setNull" -> {
                            bound.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            if (plan.failure() != null) throw plan.failure();
                            yield resultSet(plan.rows());
                        }
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                JdbcRsotRepositoryTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++index[0] < rows.size();
                    case "getObject" -> value(rows, index[0], args[0]);
                    case "getString" -> string(value(rows, index[0], args[0]));
                    case "getInt" -> number(value(rows, index[0], args[0])).intValue();
                    case "getLong" -> number(value(rows, index[0], args[0])).longValue();
                    case "getBoolean" -> Boolean.TRUE.equals(value(rows, index[0], args[0]));
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object value(List<Map<String, Object>> rows, int index, Object key) {
        if (index < 0 || index >= rows.size()) return null;
        return rows.get(index).get(String.valueOf(key));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Number number(Object value) {
        return value == null ? 0 : (Number) value;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
