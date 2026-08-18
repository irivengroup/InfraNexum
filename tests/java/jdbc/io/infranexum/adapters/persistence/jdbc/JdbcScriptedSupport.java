package io.infranexum.adapters.persistence.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import javax.sql.DataSource;

/** Shared deterministic JDBC double for repository contract tests. */
final class JdbcScriptedSupport {
    private JdbcScriptedSupport() {}

    static JdbcConnectionAccess noTransaction() {
        return () -> { throw new IllegalStateException("no active JDBC unit of work"); };
    }

    static JdbcConnectionAccess transaction(Connection connection) { return () -> connection; }

    static DataSource dataSource(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(JdbcScriptedSupport.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "getLogWriter" -> null;
                    case "setLogWriter", "setLoginTimeout" -> null;
                    case "getLoginTimeout" -> 0;
                    case "getParentLogger" -> java.util.logging.Logger.getGlobal();
                    default -> defaultValue(method.getReturnType());
                });
    }

    static DataSource failingDataSource(SQLException failure) {
        return (DataSource) Proxy.newProxyInstance(JdbcScriptedSupport.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw failure;
                    return defaultValue(method.getReturnType());
                });
    }

    static ScriptedConnection connection(Script... scripts) { return new ScriptedConnection(List.of(scripts)); }
    static Script query(Map<String, Object> row) { return query(List.of(row)); }
    static Script query(List<Map<String, Object>> rows) { return new Script(rows, 1, null, null, null); }
    static Script queryFailure(SQLException failure) { return new Script(List.of(), 1, failure, null, null); }
    static Script update(int count) { return new Script(List.of(), count, null, null, null); }
    static Script updateFailure(SQLException failure) { return new Script(List.of(), 1, null, failure, null); }
    static Script batch() { return new Script(List.of(), 1, null, null, null); }
    static Script batchFailure(SQLException failure) { return new Script(List.of(), 1, null, null, failure); }

    record Script(List<Map<String, Object>> rows, int updateCount, SQLException queryFailure,
                  SQLException updateFailure, SQLException batchFailure) {}

    static final class ScriptedConnection {
        private final Queue<Script> scripts;
        private final List<String> sql = new ArrayList<>();
        private final List<Map<Integer, Object>> parameters = new ArrayList<>();
        private final List<List<Map<Integer, Object>>> batches = new ArrayList<>();
        private final Connection connection;
        private boolean autoCommit;
        private int savepointSequence;
        private SQLException rollbackFailure;
        private SQLException restoreAutoCommitFailure;

        ScriptedConnection(List<Script> scripts) {
            this.scripts = new ArrayDeque<>(scripts);
            this.connection = (Connection) Proxy.newProxyInstance(
                    JdbcScriptedSupport.class.getClassLoader(), new Class<?>[] {Connection.class}, this::invokeConnection);
        }

        Connection connection() { return connection; }
        ScriptedConnection autoCommit(boolean value) { this.autoCommit = value; return this; }
        ScriptedConnection rollbackFails(SQLException failure) { this.rollbackFailure = failure; return this; }
        ScriptedConnection restoreAutoCommitFails(SQLException failure) { this.restoreAutoCommitFailure = failure; return this; }
        boolean autoCommit() { return autoCommit; }
        List<String> sql() { return sql; }
        List<Map<Integer, Object>> parameters() { return parameters; }
        List<List<Map<Integer, Object>>> batches() { return batches; }
        boolean exhausted() { return scripts.isEmpty(); }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "prepareStatement" -> prepare(String.valueOf(args[0]));
                case "close", "commit", "releaseSavepoint", "setReadOnly" -> null;
                case "rollback" -> { if (rollbackFailure != null) throw rollbackFailure; yield null; }
                case "setAutoCommit" -> {
                    boolean requested = (Boolean) args[0];
                    if (requested && restoreAutoCommitFailure != null) throw restoreAutoCommitFailure;
                    autoCommit = requested;
                    yield null;
                }
                case "setSavepoint" -> {
                    int id = ++savepointSequence;
                    yield new Savepoint() {
                        @Override public int getSavepointId() { return id; }
                        @Override public String getSavepointName() { return "SP_" + id; }
                    };
                }
                case "isClosed" -> false;
                case "getAutoCommit" -> autoCommit;
                case "isWrapperFor" -> false;
                case "unwrap" -> throw new SQLException("not a wrapper");
                case "toString" -> "ScriptedConnection";
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement prepare(String statementSql) throws SQLException {
            if (scripts.isEmpty()) throw new SQLException("unexpected SQL: " + statementSql);
            Script script = scripts.remove();
            sql.add(statementSql);
            Map<Integer, Object> current = new LinkedHashMap<>();
            parameters.add(current);
            List<Map<Integer, Object>> statementBatches = new ArrayList<>();
            batches.add(statementBatches);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setObject", "setString", "setInt", "setLong", "setBoolean", "setDate", "setTimestamp", "setCharacterStream", "setBigDecimal" -> {
                    current.put((Integer) args[0], args[1]); yield null;
                }
                case "setNull" -> { current.put((Integer) args[0], null); yield null; }
                case "addBatch" -> { statementBatches.add(new LinkedHashMap<>(current)); yield null; }
                case "executeQuery" -> {
                    if (script.queryFailure() != null) throw script.queryFailure();
                    yield resultSet(script.rows());
                }
                case "executeUpdate" -> {
                    if (script.updateFailure() != null) throw script.updateFailure();
                    yield script.updateCount();
                }
                case "executeBatch" -> {
                    if (script.batchFailure() != null) throw script.batchFailure();
                    int[] counts = new int[statementBatches.size()]; Arrays.fill(counts, 1); yield counts;
                }
                case "close", "clearParameters", "clearBatch", "setMaxRows", "setFetchSize" -> null;
                case "isClosed" -> false;
                case "getConnection" -> connection;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    JdbcScriptedSupport.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
        }
    }

    static ResultSet resultSet(List<Map<String, Object>> rows) {
        InvocationHandler handler = new InvocationHandler() {
            private int index = -1;
            private boolean wasNull;
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("next")) return ++index < rows.size();
                if (name.equals("close")) return null;
                if (name.equals("isClosed")) return false;
                if (name.equals("wasNull")) return wasNull;
                if (name.equals("toString")) return "ScriptedResultSet";
                if (name.startsWith("get")) {
                    Object key = args[0];
                    Object value = key instanceof Integer i ? positional(current(), i) : current().get(String.valueOf(key));
                    wasNull = value == null;
                    return switch (name) {
                        case "getObject" -> value;
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getBoolean" -> value != null && (value instanceof Boolean b ? b : ((Number) value).intValue() != 0);
                        case "getDate" -> value == null ? null : value instanceof Date d ? d : Date.valueOf(value.toString());
                        case "getBigDecimal" -> value == null ? null : (java.math.BigDecimal) value;
                        default -> defaultValue(method.getReturnType());
                    };
                }
                return defaultValue(method.getReturnType());
            }
            private Map<String, Object> current() throws SQLException {
                if (index < 0 || index >= rows.size()) throw new SQLException("result set cursor is not on a row");
                return rows.get(index);
            }
            private Object positional(Map<String,Object> row, int i) {
                if (i < 1 || i > row.size()) return null;
                return new ArrayList<>(row.values()).get(i - 1);
            }
        };
        return (ResultSet) Proxy.newProxyInstance(JdbcScriptedSupport.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("unsupported primitive: " + type);
    }
}
