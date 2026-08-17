package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalSession;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcLocalIdentityRepositoriesTest {
    private static final Instant NOW = Instant.parse("2026-08-12T20:00:00Z");

    @Test
    void accountRepositoryCountsFindsAndMapsBothDialects() {
        ScriptedDataSource count = dataSource(connection(query(List.of(Map.of("1", 1L)))));
        assertTrue(new JdbcLocalIdentityRepository(count, JdbcDatabaseDialect.POSTGRESQL).hasAnyAccount());
        assertEquals(1, count.sql.size());

        ScriptedDataSource emptyCount = dataSource(connection(query(List.of(Map.of("1", 0L)))));
        assertFalse(new JdbcLocalIdentityRepository(emptyCount, JdbcDatabaseDialect.ORACLE).hasAnyAccount());
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalIdentityRepository(
                dataSource(connection(query(List.of()))), JdbcDatabaseDialect.POSTGRESQL).hasAnyAccount());

        Map<String,Object> pgRow = accountRow(JdbcDatabaseDialect.POSTGRESQL, true);
        JdbcLocalIdentityRepository pg = new JdbcLocalIdentityRepository(dataSource(
                connection(query(List.of(pgRow))), connection(query(List.of(pgRow)))), JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("admin", pg.findByUsername("admin").orElseThrow().username());
        assertEquals(id(1), pg.findById(id(1)).orElseThrow().id());

        Map<String,Object> oracleRow = accountRow(JdbcDatabaseDialect.ORACLE, false);
        JdbcLocalIdentityRepository oracle = new JdbcLocalIdentityRepository(
                dataSource(connection(query(List.of(oracleRow)))), JdbcDatabaseDialect.ORACLE);
        assertFalse(oracle.findByUsername("admin").orElseThrow().mustChange());
        assertTrue(new JdbcLocalIdentityRepository(dataSource(connection(query(List.of()))), JdbcDatabaseDialect.ORACLE)
                .findByUsername("missing").isEmpty());
    }

    @Test
    void accountInsertBindsEveryFieldAndTranslatesFailures() {
        LocalAccount account = account(true, 0, null, 0, 0);
        ScriptedDataSource pgData = dataSource(connection(update(1)));
        new JdbcLocalIdentityRepository(pgData, JdbcDatabaseDialect.POSTGRESQL).insert(account);
        assertTrue(pgData.values.stream().anyMatch(v -> Boolean.TRUE.equals(v)));
        assertTrue(pgData.sql.getFirst().contains("infranexum_iam.local_account"));

        ScriptedDataSource oracleData = dataSource(connection(update(1)));
        new JdbcLocalIdentityRepository(oracleData, JdbcDatabaseDialect.ORACLE).insert(account);
        assertTrue(oracleData.values.stream().anyMatch(v -> Integer.valueOf(1).equals(v)));
        assertTrue(oracleData.sql.getFirst().contains("INFRANEXUM_IAM_LOCAL_ACCOUNT"));

        SQLException pgUnique = new SQLException("duplicate", "23505");
        assertThrows(IllegalStateException.class, () -> new JdbcLocalIdentityRepository(
                dataSource(connection(failingUpdate(pgUnique))), JdbcDatabaseDialect.POSTGRESQL).insert(account));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalIdentityRepository(
                dataSource(connection(update(0))), JdbcDatabaseDialect.POSTGRESQL).insert(account));
        assertThrows(NullPointerException.class, () -> new JdbcLocalIdentityRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcLocalIdentityRepository(pgData, null));
    }

    @Test
    void accountMutationsLockRowsApplyLockoutRehashAndSecurityEpochAtomically() {
        Map<String,Object> row = accountRow(JdbcDatabaseDialect.POSTGRESQL, true);
        ScriptedDataSource failed = dataSource(connection(query(List.of(row)), update(1)));
        LocalAccount afterFailure = new JdbcLocalIdentityRepository(failed, JdbcDatabaseDialect.POSTGRESQL)
                .recordFailedAuthentication(id(1), 0, 3, Duration.ofMinutes(15), NOW.plusSeconds(1));
        assertEquals(1, afterFailure.failedAttempts());
        assertEquals(null, afterFailure.lockedUntil());
        assertEquals(1, failed.commits);

        row = accountRow(JdbcDatabaseDialect.POSTGRESQL, true);
        row.put("failed_attempts", 2);
        ScriptedDataSource lockedData = dataSource(connection(query(List.of(row)), update(1)));
        LocalAccount locked = new JdbcLocalIdentityRepository(lockedData, JdbcDatabaseDialect.POSTGRESQL)
                .recordFailedAuthentication(id(1), 0, 3, Duration.ofMinutes(15), NOW.plusSeconds(1));
        assertEquals(0, locked.failedAttempts());
        assertEquals(NOW.plusSeconds(1).plus(Duration.ofMinutes(15)), locked.lockedUntil());

        ScriptedDataSource successData = dataSource(connection(query(List.of(accountRow(JdbcDatabaseDialect.POSTGRESQL, true))), update(1)));
        LocalAccount success = new JdbcLocalIdentityRepository(successData, JdbcDatabaseDialect.POSTGRESQL)
                .recordSuccessfulAuthentication(id(1), 0, null, NOW.plusSeconds(2));
        assertEquals("hash", success.passwordHash());
        assertTrue(success.mustChange());
        ScriptedDataSource rehashData = dataSource(connection(query(List.of(accountRow(JdbcDatabaseDialect.POSTGRESQL, true))), update(1)));
        assertEquals("replacement", new JdbcLocalIdentityRepository(rehashData, JdbcDatabaseDialect.POSTGRESQL)
                .recordSuccessfulAuthentication(id(1), 0, "replacement", NOW.plusSeconds(2)).passwordHash());

        ScriptedDataSource changeData = dataSource(connection(query(List.of(accountRow(JdbcDatabaseDialect.POSTGRESQL, true))), update(1)));
        LocalAccount changed = new JdbcLocalIdentityRepository(changeData, JdbcDatabaseDialect.POSTGRESQL)
                .changePassword(id(1), 0, "new-hash", false, NOW.plusSeconds(3));
        assertEquals(1, changed.securityEpoch());
        assertFalse(changed.mustChange());

        Map<String,Object> rotatedRow = accountRow(JdbcDatabaseDialect.POSTGRESQL, true);
        rotatedRow.put("security_epoch", 1L);
        ScriptedDataSource staleCredential = dataSource(connection(query(List.of(rotatedRow))));
        assertThrows(io.infranexum.identity.local.domain.LocalCredentialStateChangedException.class, () ->
                new JdbcLocalIdentityRepository(staleCredential, JdbcDatabaseDialect.POSTGRESQL)
                        .changePassword(id(1), 0, "stale", false, NOW));
        assertEquals(1, staleCredential.rollbacks);

        ScriptedDataSource concurrent = dataSource(connection(query(List.of(accountRow(JdbcDatabaseDialect.POSTGRESQL, true))), update(0)));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalIdentityRepository(concurrent, JdbcDatabaseDialect.POSTGRESQL)
                .changePassword(id(1), 0, "new", false, NOW));
        assertEquals(1, concurrent.rollbacks);
        assertThrows(NullPointerException.class, () -> new JdbcLocalIdentityRepository(changeData, JdbcDatabaseDialect.POSTGRESQL)
                .changePassword(id(1), 0, null, false, NOW));

        ScriptedDataSource missing = dataSource(connection(query(List.of())));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalIdentityRepository(missing, JdbcDatabaseDialect.POSTGRESQL)
                .recordFailedAuthentication(id(1), 0, 3, Duration.ofMinutes(5), NOW));
        assertEquals(1, missing.rollbacks);
    }

    @Test
    void sessionRepositoryCoversDurableLifecycleBothDialectsAndFailures() {
        LocalSession session = session(null);
        ScriptedDataSource insert = dataSource(connection(update(1)));
        new JdbcLocalSessionRepository(insert, JdbcDatabaseDialect.POSTGRESQL).insert(session);
        assertTrue(insert.sql.getFirst().contains("infranexum_iam.local_session"));

        ScriptedDataSource find = dataSource(connection(query(List.of(sessionRow(JdbcDatabaseDialect.POSTGRESQL, null)))));
        LocalSession read = new JdbcLocalSessionRepository(find, JdbcDatabaseDialect.POSTGRESQL)
                .findByTokenHash("0".repeat(64)).orElseThrow();
        assertEquals(session.id(), read.id());
        assertTrue(new JdbcLocalSessionRepository(dataSource(connection(query(List.of()))), JdbcDatabaseDialect.POSTGRESQL)
                .findByTokenHash("0".repeat(64)).isEmpty());

        ScriptedDataSource oracle = dataSource(connection(query(List.of(sessionRow(JdbcDatabaseDialect.ORACLE, NOW.plusSeconds(2))))));
        assertEquals(NOW.plusSeconds(2), new JdbcLocalSessionRepository(oracle, JdbcDatabaseDialect.ORACLE)
                .findByTokenHash("0".repeat(64)).orElseThrow().revokedAt());

        ScriptedDataSource touch = dataSource(connection(update(1)), connection(update(1)), connection(update(3)));
        JdbcLocalSessionRepository repository = new JdbcLocalSessionRepository(touch, JdbcDatabaseDialect.POSTGRESQL);
        repository.touch(session.id(), NOW.plusSeconds(1), NOW.plusSeconds(301));
        repository.revoke(session.id(), NOW.plusSeconds(2));
        repository.revokeAllForAccount(session.accountId(), NOW.plusSeconds(3));
        assertEquals(3, touch.sql.size());

        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalSessionRepository(
                dataSource(connection(update(0))), JdbcDatabaseDialect.POSTGRESQL).touch(session.id(), NOW, NOW.plusSeconds(1)));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalSessionRepository(
                dataSource(connection(failingUpdate(new SQLException("offline", "08006")))), JdbcDatabaseDialect.POSTGRESQL).insert(session));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcLocalSessionRepository(
                dataSource(connection(update(0))), JdbcDatabaseDialect.POSTGRESQL).insert(session));
        assertThrows(NullPointerException.class, () -> new JdbcLocalSessionRepository(null, JdbcDatabaseDialect.POSTGRESQL));
        assertThrows(NullPointerException.class, () -> new JdbcLocalSessionRepository(insert, null));
    }

    private static Map<String,Object> accountRow(JdbcDatabaseDialect dialect, boolean mustChange) {
        Map<String,Object> row = new HashMap<>();
        row.put("id", dialect == JdbcDatabaseDialect.POSTGRESQL ? id(1).value() : id(1).toString());
        row.put("username", "admin"); row.put("display_name", "Local Administrator"); row.put("password_hash", "hash");
        row.put("must_change", dialect == JdbcDatabaseDialect.POSTGRESQL ? mustChange : (mustChange ? 1 : 0));
        row.put("status", "ACTIVE"); row.put("failed_attempts", 0); row.put("locked_until", null);
        row.put("security_epoch", 0L); row.put("version", 0L); row.put("created_at", NOW); row.put("updated_at", NOW);
        return row;
    }

    private static Map<String,Object> sessionRow(JdbcDatabaseDialect dialect, Instant revoked) {
        Map<String,Object> row = new HashMap<>();
        row.put("id", dialect == JdbcDatabaseDialect.POSTGRESQL ? id(2).value() : id(2).toString());
        row.put("account_id", dialect == JdbcDatabaseDialect.POSTGRESQL ? id(1).value() : id(1).toString());
        row.put("token_hash", "0".repeat(64)); row.put("csrf_hash", "1".repeat(64)); row.put("security_epoch", 0L);
        row.put("created_at", NOW); row.put("last_seen_at", NOW); row.put("idle_expires_at", NOW.plusSeconds(300));
        row.put("absolute_expires_at", NOW.plusSeconds(600)); row.put("revoked_at", revoked);
        return row;
    }

    private static LocalAccount account(boolean mustChange, int failed, Instant locked, long epoch, long version) {
        return new LocalAccount(id(1), "admin", "Local Administrator", "hash", mustChange, LocalAccountStatus.ACTIVE,
                failed, locked, epoch, version, NOW, NOW);
    }
    private static LocalSession session(Instant revoked) {
        return new LocalSession(id(2), id(1), "0".repeat(64), "1".repeat(64), 0, NOW, NOW,
                NOW.plusSeconds(300), NOW.plusSeconds(600), revoked);
    }
    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    private static ScriptedDataSource dataSource(ConnectionScript... scripts) { return new ScriptedDataSource(List.of(scripts)); }
    private static ConnectionScript connection(Plan... plans) { return new ConnectionScript(List.of(plans)); }
    private static Plan query(List<Map<String,Object>> rows) { return new Plan(rows, null, null); }
    private static Plan update(int count) { return new Plan(List.of(), count, null); }
    private static Plan failingUpdate(SQLException failure) { return new Plan(List.of(), null, failure); }
    private record Plan(List<Map<String,Object>> rows, Integer updateCount, SQLException failure) {}
    private record ConnectionScript(List<Plan> plans) {}

    private static final class ScriptedDataSource implements DataSource {
        final Queue<ConnectionScript> scripts;
        final List<String> sql = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        int commits; int rollbacks;
        ScriptedDataSource(List<ConnectionScript> scripts) { this.scripts = new ArrayDeque<>(scripts); }
        @Override public Connection getConnection() throws SQLException {
            ConnectionScript script = scripts.poll(); if (script == null) throw new SQLException("unexpected connection");
            Queue<Plan> plans = new ArrayDeque<>(script.plans());
            boolean[] autoCommit = {true};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    sql.add((String) args[0]); Plan plan = plans.poll(); if (plan == null) throw new SQLException("unexpected statement");
                    yield statement(plan);
                }
                case "getAutoCommit" -> autoCommit[0];
                case "setAutoCommit" -> { autoCommit[0] = (boolean) args[0]; yield null; }
                case "commit" -> { commits++; yield null; }
                case "rollback" -> { rollbacks++; yield null; }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }
        private PreparedStatement statement(Plan plan) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setInt", "setLong", "setBoolean", "setObject", "setNull" -> { values.add(args[1]); yield null; }
                case "executeUpdate" -> { if (plan.failure() != null) throw plan.failure(); yield plan.updateCount() == null ? 0 : plan.updateCount(); }
                case "executeQuery" -> { if (plan.failure() != null) throw plan.failure(); yield resultSet(plan.rows()); }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }
        @Override public Connection getConnection(String u,String p) throws SQLException { return getConnection(); }
        @Override public <T>T unwrap(Class<T> c){throw new UnsupportedOperationException();}
        @Override public boolean isWrapperFor(Class<?> c){return false;}
        @Override public java.io.PrintWriter getLogWriter(){return null;}
        @Override public void setLogWriter(java.io.PrintWriter w){}
        @Override public void setLoginTimeout(int s){}
        @Override public int getLoginTimeout(){return 0;}
        @Override public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
    }

    private static ResultSet resultSet(List<Map<String,Object>> rows) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(JdbcLocalIdentityRepositoriesTest.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++index[0] < rows.size();
            case "getObject" -> value(rows, index[0], args[0]);
            case "getString" -> string(value(rows, index[0], args[0]));
            case "getInt" -> number(value(rows, index[0], args[0])).intValue();
            case "getLong" -> number(value(rows, index[0], args[0])).longValue();
            case "getBoolean" -> Boolean.TRUE.equals(value(rows, index[0], args[0]));
            case "wasNull" -> false;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }
    private static Object value(List<Map<String,Object>> rows, int index, Object key) {
        if (index < 0 || index >= rows.size()) return null;
        Map<String,Object> row = rows.get(index);
        if (key instanceof Integer integer) return row.get(String.valueOf(integer));
        return row.get(String.valueOf(key));
    }
    private static String string(Object v) { return v == null ? null : String.valueOf(v); }
    private static Number number(Object v) { return v == null ? 0 : (Number) v; }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte)0; if (type == short.class) return (short)0; if (type == int.class) return 0;
        if (type == long.class) return 0L; if (type == float.class) return 0f; if (type == double.class) return 0d; if (type == char.class) return '\0';
        return null;
    }
}
