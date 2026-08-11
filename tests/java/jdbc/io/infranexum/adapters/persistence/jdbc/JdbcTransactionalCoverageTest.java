package io.infranexum.adapters.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.InboxDecision;
import io.infranexum.core.events.InboxKey;
import io.infranexum.core.events.InboxReservation;
import io.infranexum.core.events.TransactionExecutionException;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Deterministic failure-path coverage for JDBC transaction and inbox/outbox boundaries. */
final class JdbcTransactionalCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void connectionSetupAndOwnedTransactionFailuresRemainFailClosed() {
        ScriptedDataSource setup = new ScriptedDataSource().autoCommit(true).isolation(Connection.TRANSACTION_SERIALIZABLE);
        JdbcTransactionalEventStore setupStore = new JdbcTransactionalEventStore(setup, JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("ok", setupStore.execute(tx -> "ok").value());
        assertTrue(setup.autoCommitDisabled);
        assertTrue(setup.isolationChanged);

        ScriptedDataSource openFailure = new ScriptedDataSource()
                .failGetAutoCommit(new SQLException("cannot inspect autocommit", "08006"))
                .failClose(new SQLException("close failed", "08006"));
        JdbcPersistenceException open = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcTransactionalEventStore(openFailure, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> null));
        assertEquals(1, open.getCause().getSuppressed().length);

        ScriptedDataSource unavailable = new ScriptedDataSource()
                .failConnection(new SQLException("offline", "08001"));
        assertThrows(JdbcPersistenceException.class,
                () -> new JdbcTransactionalEventStore(unavailable, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker", 1, NOW, Duration.ofSeconds(5)));

        ScriptedDataSource queryFailure = new ScriptedDataSource(
                Step.queryFailure("/*inx:outbox-claim-postgresql*/", new SQLException("query failed", "08006")))
                .failRollback(new SQLException("rollback failed", "08006"));
        JdbcPersistenceException query = assertThrows(JdbcPersistenceException.class,
                () -> new JdbcTransactionalEventStore(queryFailure, JdbcDatabaseDialect.POSTGRESQL)
                        .claimBatch("worker", 1, NOW, Duration.ofSeconds(5)));
        assertEquals(1, query.getCause().getSuppressed().length);

        JdbcTransactionalEventStore nested = new JdbcTransactionalEventStore(
                new ScriptedDataSource(), JdbcDatabaseDialect.POSTGRESQL);
        nested.execute(tx -> {
            assertThrows(IllegalStateException.class,
                    () -> nested.claimBatch("worker", 1, NOW, Duration.ofSeconds(5)));
            return null;
        });
    }

    @Test
    void claimMappingCoversEmptyOracleCausationAndLostUpdateBranches() {
        ScriptedDataSource emptyOracle = new ScriptedDataSource(Step.query("/*inx:outbox-select-oracle*/"));
        assertTrue(new JdbcTransactionalEventStore(emptyOracle, JdbcDatabaseDialect.ORACLE)
                .claimBatch("oracle", 2, NOW, Duration.ofSeconds(5)).isEmpty());
        emptyOracle.assertExhausted();

        EventEnvelope withCausation = event(11, true);
        ScriptedDataSource postgres = new ScriptedDataSource(Step.query(
                "/*inx:outbox-claim-postgresql*/", outboxRow(withCausation, "IN_FLIGHT", 1, "pg")));
        var claimed = new JdbcTransactionalEventStore(postgres, JdbcDatabaseDialect.POSTGRESQL)
                .claimBatch("pg", 1, NOW, Duration.ofSeconds(5));
        assertEquals(withCausation.causationId(), claimed.getFirst().event().causationId());
        postgres.assertExhausted();

        EventEnvelope oracleEvent = event(12, false);
        ScriptedDataSource lostOracle = new ScriptedDataSource(
                Step.query("/*inx:outbox-select-oracle*/", outboxRow(oracleEvent, "PENDING", 0, null)),
                Step.update("/*inx:outbox-claim-oracle*/", 0));
        assertThrows(IllegalStateException.class,
                () -> new JdbcTransactionalEventStore(lostOracle, JdbcDatabaseDialect.ORACLE)
                        .claimBatch("oracle", 1, NOW, Duration.ofSeconds(5)));
        lostOracle.assertExhausted();

        EventEnvelope pendingEvent = event(13, false);
        ScriptedDataSource pendingLease = new ScriptedDataSource(Step.query(
                "/*inx:outbox-state*/", row("status", "PENDING", "attempts", 1, "lease_owner", "owner")));
        assertThrows(IllegalStateException.class, () -> new JdbcTransactionalEventStore(
                pendingLease, JdbcDatabaseDialect.POSTGRESQL)
                .markPublished(pendingEvent.eventId(), "owner", NOW));
        pendingLease.assertExhausted();
    }

    @Test
    void inboxAndAppendFailureBranchesAreObservableAndTransactional() {
        InboxReservation reservation = reservation(21);

        ScriptedDataSource appendFailure = new ScriptedDataSource(
                Step.updateFailure("/*inx:outbox-insert*/", new SQLException("insert failed", "08006")));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(appendFailure, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> { tx.append(event(20, false)); return null; }));

        ScriptedDataSource duplicateInTransaction = new ScriptedDataSource(Step.update("/*inx:inbox-reserve*/", 1));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(duplicateInTransaction, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> {
                            assertEquals(InboxDecision.ACCEPTED, tx.beginInbox(reservation));
                            tx.beginInbox(reservation);
                            return null;
                        }));

        ScriptedDataSource processing = new ScriptedDataSource(
                Step.update("/*inx:inbox-reserve*/", 0),
                Step.query("/*inx:inbox-status*/", row("status", "PROCESSING")));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(processing, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> { tx.beginInbox(reservation); return null; }));

        ScriptedDataSource reserveFailure = new ScriptedDataSource(
                Step.updateFailure("/*inx:inbox-reserve*/", new SQLException("reserve failed", "08006")));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(reserveFailure, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> { tx.beginInbox(reservation); return null; }));

        ScriptedDataSource disappeared = new ScriptedDataSource(
                Step.update("/*inx:inbox-reserve*/", 0), Step.query("/*inx:inbox-status*/"));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(disappeared, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> { tx.beginInbox(reservation); return null; }));

        ScriptedDataSource doubleComplete = new ScriptedDataSource(
                Step.update("/*inx:inbox-reserve*/", 1), Step.update("/*inx:inbox-complete*/", 1));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(doubleComplete, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> {
                            tx.beginInbox(reservation);
                            tx.completeInbox(reservation.key(), NOW.plusSeconds(1));
                            tx.completeInbox(reservation.key(), NOW.plusSeconds(2));
                            return null;
                        }));

        ScriptedDataSource beforeReceived = new ScriptedDataSource(Step.update("/*inx:inbox-reserve*/", 1));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(beforeReceived, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> {
                            tx.beginInbox(reservation);
                            tx.completeInbox(reservation.key(), reservation.receivedAt().minusNanos(1));
                            return null;
                        }));

        ScriptedDataSource completeFailure = new ScriptedDataSource(
                Step.update("/*inx:inbox-reserve*/", 1),
                Step.updateFailure("/*inx:inbox-complete*/", new SQLException("complete failed", "08006")));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(completeFailure, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> {
                            tx.beginInbox(reservation);
                            tx.completeInbox(reservation.key(), NOW.plusSeconds(1));
                            return null;
                        }));

        ScriptedDataSource incomplete = new ScriptedDataSource(Step.update("/*inx:inbox-reserve*/", 1));
        assertThrows(TransactionExecutionException.class,
                () -> new JdbcTransactionalEventStore(incomplete, JdbcDatabaseDialect.POSTGRESQL)
                        .execute(tx -> { tx.beginInbox(reservation); return null; }));
    }

    private static InboxReservation reservation(int sequence) {
        EventEnvelope event = event(sequence, false);
        return new InboxReservation(
                new InboxKey("core.coverage", event.eventId()), event.eventType(), "a".repeat(64), NOW);
    }

    private static EventEnvelope event(int sequence, boolean withCausation) {
        String suffix = "%012d".formatted(sequence);
        return new EventEnvelope(
                id("018bcfe5-6800-7000-8000-" + suffix),
                new EventType("core.coverage.created.v1"),
                ContractVersion.parse("1.0.0"), NOW.plusMillis(sequence),
                new EventSource("core/server-coverage"),
                id("018bcfe5-6800-7002-8000-" + suffix),
                withCausation ? id("018bcfe5-6800-7003-8000-" + suffix) : null,
                "{\"sequence\":" + sequence + "}");
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }

    private static Map<String, Object> outboxRow(
            EventEnvelope event, String status, int attempts, String owner) {
        return row(
                "event_id", event.eventId().value(), "event_type", event.eventType().value(),
                "schema_version", event.schemaVersion().toString(), "occurred_at", offset(event.occurredAt()),
                "event_source", event.source().value(), "correlation_id", event.correlationId().value(),
                "causation_id", event.causationId() == null ? null : event.causationId().value(),
                "payload_json", event.payload(), "status", status, "attempts", attempts,
                "available_at", offset(NOW), "lease_owner", owner,
                "lease_until", owner == null ? null : offset(NOW.plusSeconds(5)),
                "published_at", null, "last_failure", null);
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private record Step(String marker, List<Map<String, Object>> rows, Integer updateCount, SQLException failure) {
        static Step query(String marker) {
            return new Step(marker, List.of(), null, null);
        }
        static Step query(String marker, Map<String, Object> row) {
            return new Step(marker, List.of(row), null, null);
        }
        static Step queryFailure(String marker, SQLException failure) {
            return new Step(marker, List.of(), null, failure);
        }
        static Step update(String marker, int count) {
            return new Step(marker, List.of(), count, null);
        }
        static Step updateFailure(String marker, SQLException failure) {
            return new Step(marker, List.of(), null, failure);
        }
    }

    private static final class ScriptedDataSource implements DataSource {
        private final Queue<Step> steps = new ArrayDeque<>();
        private final AtomicInteger savepoints = new AtomicInteger();
        private boolean autoCommit;
        private int isolation = Connection.TRANSACTION_READ_COMMITTED;
        private SQLException connectionFailure;
        private SQLException getAutoCommitFailure;
        private SQLException closeFailure;
        private SQLException rollbackFailure;
        private boolean autoCommitDisabled;
        private boolean isolationChanged;

        ScriptedDataSource(Step... configured) { steps.addAll(List.of(configured)); }
        ScriptedDataSource autoCommit(boolean value) { autoCommit = value; return this; }
        ScriptedDataSource isolation(int value) { isolation = value; return this; }
        ScriptedDataSource failConnection(SQLException value) { connectionFailure = value; return this; }
        ScriptedDataSource failGetAutoCommit(SQLException value) { getAutoCommitFailure = value; return this; }
        ScriptedDataSource failClose(SQLException value) { closeFailure = value; return this; }
        ScriptedDataSource failRollback(SQLException value) { rollbackFailure = value; return this; }
        void assertExhausted() { assertTrue(steps.isEmpty(), "unconsumed JDBC step: " + steps.peek()); }

        @Override public Connection getConnection() throws SQLException {
            if (connectionFailure != null) throw connectionFailure;
            return connection();
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statement((String) args[0]);
                        case "getAutoCommit" -> {
                            if (getAutoCommitFailure != null) throw getAutoCommitFailure;
                            yield autoCommit;
                        }
                        case "setAutoCommit" -> { autoCommit = (boolean) args[0]; autoCommitDisabled = !autoCommit; yield null; }
                        case "getTransactionIsolation" -> isolation;
                        case "setTransactionIsolation" -> { isolation = (int) args[0]; isolationChanged = true; yield null; }
                        case "setSavepoint" -> savepoint(savepoints.incrementAndGet());
                        case "releaseSavepoint" -> null;
                        case "rollback" -> { if (rollbackFailure != null) throw rollbackFailure; yield null; }
                        case "commit" -> null;
                        case "close" -> { if (closeFailure != null) throw closeFailure; yield null; }
                        case "isClosed" -> false;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement statement(String sql) {
            Step step = steps.poll();
            if (step == null) throw new AssertionError("unexpected SQL: " + sql);
            assertTrue(sql.contains(step.marker()), "expected SQL marker " + step.marker() + " but got " + sql);
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeQuery" -> {
                            if (step.failure() != null) throw step.failure();
                            yield resultSet(step.rows());
                        }
                        case "executeUpdate" -> {
                            if (step.failure() != null) throw step.failure();
                            yield step.updateCount() == null ? 0 : step.updateCount();
                        }
                        case "setString", "setObject", "setNull", "setInt", "setLong", "setMaxRows", "setFetchSize", "setCharacterStream", "close" -> null;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static ResultSet resultSet(List<Map<String, Object>> rows) {
            AtomicInteger cursor = new AtomicInteger(-1);
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("next")) return cursor.incrementAndGet() < rows.size();
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("unwrap")) return null;
                        if (method.getName().equals("isWrapperFor")) return false;
                        Object value = rows.get(cursor.get()).get((String) args[0]);
                        return switch (method.getName()) {
                            case "getObject" -> value;
                            case "getString" -> value == null ? null : value.toString();
                            case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private static Savepoint savepoint(int id) {
            return new Savepoint() {
                @Override public int getSavepointId() { return id; }
                @Override public String getSavepointName() { return "inx-" + id; }
            };
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

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("JdbcTransactionalCoverageTest"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
