package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditRecord;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/** Dependency-free JDBC simulation exercising the append-only audit adapter contract. */
public final class JdbcAuditJournalSmoke {
    private JdbcAuditJournalSmoke() {}

    public static void main(String[] args) throws Exception {
        provesPostgreSqlAppendReadAndVerification();
        provesOracleMappingAndFailureGuards();
        provesTamperAndConfigurationGuards();
        System.out.println("java-jdbc-audit-smoke: PASS");
    }

    static void provesPostgreSqlAppendReadAndVerification() {
        SimulatedAuditDataSource source = new SimulatedAuditDataSource(JdbcDatabaseDialect.POSTGRESQL);
        JdbcAuditJournal journal = new JdbcAuditJournal(source, JdbcDatabaseDialect.POSTGRESQL);
        AuditScope scope = AuditScope.organization("org-jdbc-pg");
        AuditRecord one = journal.append(entry(1, scope, true));
        AuditRecord two = journal.append(entry(2, scope, true));
        require(one.sequence() == 1 && two.sequence() == 2, "audit sequence is not monotonic");
        require(two.previousHash().equals(one.entryHash()), "audit chain head was not serialized");
        List<AuditRecord> records = journal.readRange(scope, 1, 2, 10);
        require(records.size() == 2 && records.get(1).entry().correlationId() != null, "PostgreSQL audit read mapping failed");
        var verification = journal.verify(scope);
        require(verification.valid() && verification.verifiedRecords() == 2 && verification.headHash().equals(two.entryHash()),
                "PostgreSQL audit verification failed");
    }

    static void provesOracleMappingAndFailureGuards() {
        SimulatedAuditDataSource source = new SimulatedAuditDataSource(JdbcDatabaseDialect.ORACLE);
        JdbcAuditJournal journal = new JdbcAuditJournal(source, JdbcDatabaseDialect.ORACLE, Connection.TRANSACTION_READ_COMMITTED);
        AuditScope scope = AuditScope.organization("org-jdbc-oracle");
        AuditRecord record = journal.append(entry(10, scope, false));
        require(record.sequence() == 1, "Oracle audit append failed");
        List<AuditRecord> records = journal.readRange(scope, 1, 1, 1);
        require(records.size() == 1 && records.get(0).entry().correlationId() == null,
                "Oracle nullable identifier mapping failed");
        require(journal.verify(scope).valid(), "Oracle audit verification failed");

        source.failHeadUpdate = true;
        expect(JdbcPersistenceException.class, () -> journal.append(entry(11, scope, true)));
        require(journal.readRange(scope, 1, 10, 10).size() == 1, "failed append escaped rollback");
        source.failHeadUpdate = false;

        source.skipHeadCreation = true;
        AuditScope missing = AuditScope.organization("org-head-missing");
        expect(JdbcPersistenceException.class, () -> journal.append(entry(12, missing, true)));
    }

    static void provesTamperAndConfigurationGuards() {
        SimulatedAuditDataSource source = new SimulatedAuditDataSource(JdbcDatabaseDialect.POSTGRESQL);
        JdbcAuditJournal journal = new JdbcAuditJournal(source, JdbcDatabaseDialect.POSTGRESQL);
        AuditScope scope = AuditScope.organization("org-jdbc-guards");
        journal.append(entry(20, scope, true));
        source.tamperHash(scope);
        require(!journal.verify(scope).valid(), "tampered audit hash was accepted");
        source.restoreHash(scope);
        source.setImmutable(scope, "N");
        expect(JdbcPersistenceException.class, () -> journal.readRange(scope, 1, 1, 1));
        source.setImmutable(scope, "Y");

        expect(IllegalArgumentException.class, () -> new JdbcAuditJournal(source, JdbcDatabaseDialect.POSTGRESQL, Connection.TRANSACTION_NONE));
        expect(NullPointerException.class, () -> new JdbcAuditJournal(null, JdbcDatabaseDialect.POSTGRESQL));
        expect(NullPointerException.class, () -> new JdbcAuditJournal(source, null));
        expect(NullPointerException.class, () -> journal.append(null));
        expect(NullPointerException.class, () -> journal.readRange(null, 1, 1, 1));
        expect(NullPointerException.class, () -> journal.verify(null));
        expect(IllegalArgumentException.class, () -> journal.readRange(scope, 0, 1, 1));
        expect(IllegalArgumentException.class, () -> journal.readRange(scope, 2, 1, 1));
        expect(IllegalArgumentException.class, () -> journal.readRange(scope, 1, 1, 0));
        expect(IllegalArgumentException.class, () -> journal.readRange(scope, 1, 1, 10_001));

        DataSource failing = new FailingDataSource();
        JdbcAuditJournal broken = new JdbcAuditJournal(failing, JdbcDatabaseDialect.POSTGRESQL);
        expect(JdbcPersistenceException.class, () -> broken.append(entry(30, scope, true)));
        expect(JdbcPersistenceException.class, () -> broken.readRange(scope, 1, 1, 1));
        expect(JdbcPersistenceException.class, () -> broken.verify(scope));
    }

    private static AuditEntry entry(int value, AuditScope scope, boolean correlation) {
        String suffix = "%012d".formatted(value);
        return new AuditEntry(
                DomainIdentifier.parse("018bcfe5-6800-7000-8000-" + suffix),
                scope,
                "user-1",
                "USER",
                "audit.jdbc.write",
                "AUDIT_ENTRY",
                "entry-" + value,
                "ALLOW",
                Instant.parse("2026-08-10T09:00:00Z").plusMillis(value),
                correlation ? DomainIdentifier.parse("018bcfe5-6800-7001-8000-" + suffix) : null,
                "SUCCESS",
                "jdbc/smoke",
                "approved",
                "192.0.2.20",
                "jdbc-audit-smoke",
                Map.of("scenario", "jdbc", "value", Integer.toString(value)),
                "INTERNAL");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> expected, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("expected " + expected.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private static final class FailingDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { throw new SQLException("database unavailable"); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class SimulatedAuditDataSource implements DataSource {
        private final JdbcDatabaseDialect dialect;
        private AuditState committed = new AuditState();
        private boolean skipHeadCreation;
        private boolean failHeadUpdate;

        private SimulatedAuditDataSource(JdbcDatabaseDialect dialect) { this.dialect = dialect; }

        @Override
        public Connection getConnection() {
            return connectionProxy(this, committed.copy());
        }

        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }

        private synchronized void commit(AuditState state) { committed = state.copy(); }

        private synchronized void tamperHash(AuditScope scope) {
            AuditRow row = committed.rowsFor(scope).get(0);
            row.originalEntryHash = row.entryHash;
            row.entryHash = "f".repeat(64);
        }

        private synchronized void restoreHash(AuditScope scope) {
            AuditRow row = committed.rowsFor(scope).get(0);
            row.entryHash = row.originalEntryHash;
        }

        private synchronized void setImmutable(AuditScope scope, String value) {
            committed.rowsFor(scope).get(0).immutableFlag = value;
        }
    }

    private static Connection connectionProxy(SimulatedAuditDataSource owner, AuditState initial) {
        class Handler implements InvocationHandler {
            private AuditState working = initial;
            private boolean autoCommit = true;
            private int isolation = Connection.TRANSACTION_READ_COMMITTED;
            private boolean closed;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                return switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                    case "getTransactionIsolation" -> isolation;
                    case "setTransactionIsolation" -> { isolation = (int) args[0]; yield null; }
                    case "prepareStatement" -> statementProxy(owner, this, (String) args[0]);
                    case "commit" -> { owner.commit(working); yield null; }
                    case "rollback" -> { working = owner.committed.copy(); yield null; }
                    case "close" -> { closed = true; yield null; }
                    case "isClosed" -> closed;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("unsupported");
                    case "toString" -> "SimulatedAuditConnection";
                    default -> defaultValue(method.getReturnType());
                };
            }
        }
        Handler handler = new Handler();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    private static PreparedStatement statementProxy(SimulatedAuditDataSource owner, InvocationHandler connectionHandler, String sql) {
        class Handler implements InvocationHandler {
            private final Map<Integer, Object> parameters = new HashMap<>();
            private int maxRows;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                return switch (method.getName()) {
                    case "setString", "setLong", "setObject" -> { parameters.put((int) args[0], args[1]); yield null; }
                    case "setNull" -> { parameters.put((int) args[0], null); yield null; }
                    case "setMaxRows" -> { maxRows = (int) args[0]; yield null; }
                    case "setFetchSize", "close" -> null;
                    case "executeUpdate" -> state(connectionHandler).update(owner, sql, parameters);
                    case "executeQuery" -> state(connectionHandler).query(owner.dialect, sql, parameters, maxRows);
                    case "getConnection" -> null;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("unsupported");
                    case "toString" -> "SimulatedAuditStatement[" + sql + "]";
                    default -> defaultValue(method.getReturnType());
                };
            }
        }
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, new Handler());
    }

    private static AuditState state(InvocationHandler handler) throws ReflectiveOperationException {
        var field = handler.getClass().getDeclaredField("working");
        field.setAccessible(true);
        return (AuditState) field.get(handler);
    }

    private static final class AuditState {
        private final Map<String, Head> heads = new LinkedHashMap<>();
        private final List<AuditRow> rows = new ArrayList<>();

        private AuditState copy() {
            AuditState copy = new AuditState();
            heads.forEach((key, head) -> copy.heads.put(key, new Head(head.sequence, head.hash)));
            for (AuditRow row : rows) copy.rows.add(row.copy());
            return copy;
        }

        private int update(SimulatedAuditDataSource owner, String sql, Map<Integer, Object> p) throws SQLException {
            String normalized = sql.toUpperCase();
            if (normalized.startsWith("INSERT INTO INFRANEXUM_CORE.AUDIT_CHAIN_HEAD") || normalized.startsWith("MERGE INTO INFRANEXUM_CORE_AUDIT_CHAIN_HEAD")) {
                if (!owner.skipHeadCreation) heads.putIfAbsent(key((String) p.get(1), (String) p.get(2)), new Head(0, (String) p.get(3)));
                return owner.skipHeadCreation ? 0 : 1;
            }
            if (normalized.startsWith("INSERT INTO INFRANEXUM_CORE.AUDIT_ENTRY") || normalized.startsWith("INSERT INTO INFRANEXUM_CORE_AUDIT_ENTRY")) {
                String auditId = identifier(p.get(4));
                if (rows.stream().anyMatch(row -> row.auditId.equals(auditId))) throw new SQLException("duplicate audit id", "23505");
                AuditRow row = new AuditRow();
                row.scopeType = (String) p.get(1); row.scopeId = (String) p.get(2); row.sequence = (long) p.get(3);
                row.auditId = auditId; row.actorId = (String) p.get(5); row.actorType = (String) p.get(6);
                row.action = (String) p.get(7); row.targetType = (String) p.get(8); row.targetId = (String) p.get(9);
                row.decision = (String) p.get(10); row.occurredAt = instant(p.get(11));
                row.correlationId = p.get(12) == null ? null : identifier(p.get(12)); row.result = (String) p.get(13);
                row.origin = (String) p.get(14); row.reason = (String) p.get(15); row.clientIp = (String) p.get(16);
                row.userAgent = (String) p.get(17); row.metadata = (String) p.get(18); row.sensitivity = (String) p.get(19);
                row.previousHash = (String) p.get(20); row.entryHash = (String) p.get(21); row.originalEntryHash = row.entryHash;
                row.immutableFlag = "Y"; rows.add(row); return 1;
            }
            if (normalized.startsWith("UPDATE INFRANEXUM_CORE.AUDIT_CHAIN_HEAD") || normalized.startsWith("UPDATE INFRANEXUM_CORE_AUDIT_CHAIN_HEAD")) {
                if (owner.failHeadUpdate) return 0;
                String key = key((String) p.get(3), (String) p.get(4));
                Head current = heads.get(key);
                if (current == null || current.sequence != (long) p.get(5) || !current.hash.equals(p.get(6))) return 0;
                heads.put(key, new Head((long) p.get(1), (String) p.get(2))); return 1;
            }
            throw new SQLException("unsupported simulated audit update: " + sql);
        }

        private ResultSet query(JdbcDatabaseDialect dialect, String sql, Map<Integer, Object> p, int maxRows) throws SQLException {
            String normalized = sql.toUpperCase();
            if (normalized.startsWith("SELECT LAST_SEQUENCE, HEAD_HASH")) {
                Head head = heads.get(key((String) p.get(1), (String) p.get(2)));
                if (head == null) return rows(List.of(), "last_sequence", "head_hash");
                return rows(List.of(Map.of("last_sequence", head.sequence, "head_hash", head.hash)), "last_sequence", "head_hash");
            }
            if (normalized.contains("AUDIT_ENTRY") && normalized.startsWith("SELECT")) {
                String type = (String) p.get(1); String id = (String) p.get(2);
                long from = normalized.contains("BETWEEN") ? (long) p.get(3) : 1L;
                long to = normalized.contains("BETWEEN") ? (long) p.get(4) : Long.MAX_VALUE;
                int limit = maxRows > 0 ? maxRows : Integer.MAX_VALUE;
                List<Map<String, Object>> values = new ArrayList<>();
                for (AuditRow row : rows) {
                    if (!row.scopeType.equals(type) || !row.scopeId.equals(id) || row.sequence < from || row.sequence > to) continue;
                    values.add(row.asMap(dialect));
                    if (values.size() == limit) break;
                }
                return rows(values, "scope_type", "scope_id", "sequence_no", "audit_id", "actor_id", "actor_type", "action_name",
                        "target_type", "target_id", "authorization_decision", "occurred_at", "correlation_id", "result_name", "origin_name",
                        "reason_text", "client_ip", "user_agent", "metadata_json", "sensitivity", "previous_hash", "entry_hash", "immutable_flag");
            }
            throw new SQLException("unsupported simulated audit query: " + sql);
        }

        private List<AuditRow> rowsFor(AuditScope scope) {
            return rows.stream().filter(row -> row.scopeType.equals(scope.type()) && row.scopeId.equals(scope.id())).toList();
        }
    }

    private static final class AuditRow {
        String scopeType; String scopeId; long sequence; String auditId; String actorId; String actorType; String action;
        String targetType; String targetId; String decision; Instant occurredAt; String correlationId; String result; String origin;
        String reason; String clientIp; String userAgent; String metadata; String sensitivity; String previousHash; String entryHash;
        String originalEntryHash; String immutableFlag;

        AuditRow copy() {
            AuditRow copy = new AuditRow();
            copy.scopeType=scopeType; copy.scopeId=scopeId; copy.sequence=sequence; copy.auditId=auditId; copy.actorId=actorId;
            copy.actorType=actorType; copy.action=action; copy.targetType=targetType; copy.targetId=targetId; copy.decision=decision;
            copy.occurredAt=occurredAt; copy.correlationId=correlationId; copy.result=result; copy.origin=origin; copy.reason=reason;
            copy.clientIp=clientIp; copy.userAgent=userAgent; copy.metadata=metadata; copy.sensitivity=sensitivity;
            copy.previousHash=previousHash; copy.entryHash=entryHash; copy.originalEntryHash=originalEntryHash; copy.immutableFlag=immutableFlag;
            return copy;
        }

        Map<String, Object> asMap(JdbcDatabaseDialect dialect) {
            Map<String, Object> values = new HashMap<>();
            values.put("scope_type", scopeType); values.put("scope_id", scopeId); values.put("sequence_no", sequence);
            values.put("audit_id", jdbcIdentifier(dialect, auditId)); values.put("actor_id", actorId); values.put("actor_type", actorType);
            values.put("action_name", action); values.put("target_type", targetType); values.put("target_id", targetId);
            values.put("authorization_decision", decision); values.put("occurred_at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            values.put("correlation_id", correlationId == null ? null : jdbcIdentifier(dialect, correlationId)); values.put("result_name", result);
            values.put("origin_name", origin); values.put("reason_text", reason); values.put("client_ip", clientIp); values.put("user_agent", userAgent);
            values.put("metadata_json", metadata); values.put("sensitivity", sensitivity); values.put("previous_hash", previousHash);
            values.put("entry_hash", entryHash); values.put("immutable_flag", immutableFlag); return values;
        }
    }

    private record Head(long sequence, String hash) {}

    private static CachedRowSet rows(List<Map<String, Object>> values, String... columns) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.length);
        for (int index = 0; index < columns.length; index++) {
            metadata.setColumnName(index + 1, columns[index]); metadata.setColumnLabel(index + 1, columns[index]);
            metadata.setColumnType(index + 1, sqlType(columns[index]));
        }
        rowSet.setMetaData(metadata);
        for (int rowIndex = values.size() - 1; rowIndex >= 0; rowIndex--) {
            Map<String, Object> value = values.get(rowIndex);
            rowSet.moveToInsertRow();
            for (int index = 0; index < columns.length; index++) rowSet.updateObject(index + 1, value.get(columns[index]));
            rowSet.insertRow(); rowSet.moveToCurrentRow();
        }
        rowSet.beforeFirst(); return rowSet;
    }

    private static int sqlType(String column) {
        if (column.equals("sequence_no") || column.equals("last_sequence")) return Types.BIGINT;
        if (column.equals("occurred_at")) return Types.TIMESTAMP_WITH_TIMEZONE;
        if (column.equals("audit_id") || column.equals("correlation_id")) return Types.VARCHAR;
        return Types.VARCHAR;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        throw new IllegalStateException("unsupported primitive");
    }

    private static String key(String type, String id) { return type + "|" + id; }
    private static String identifier(Object value) { return value instanceof UUID uuid ? uuid.toString() : Objects.toString(value); }
    private static Object jdbcIdentifier(JdbcDatabaseDialect dialect, String value) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? UUID.fromString(value) : value;
    }
    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Instant direct) return direct;
        throw new IllegalArgumentException("unsupported instant " + value);
    }
}
