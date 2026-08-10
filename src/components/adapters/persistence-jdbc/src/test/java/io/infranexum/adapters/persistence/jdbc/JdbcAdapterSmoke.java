package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.ExponentialBackoffPolicy;
import io.infranexum.core.events.InboxProcessingResult;
import io.infranexum.core.events.InboxProcessor;
import io.infranexum.core.events.OutboxStatus;
import io.infranexum.core.events.TransactionExecutionException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/** Executable JDBC contract smoke using a transactional driver-level simulation. */
public final class JdbcAdapterSmoke {
    private static final Instant NOW = Instant.parse("2026-08-03T14:00:00Z");

    private JdbcAdapterSmoke() {}

    public static void main(String[] args) throws Exception {
        provesPostgreSqlUnitOfWorkAndOutbox();
        provesOracleClaimsAndInboxDeduplication();
        provesConfigurationAndOwnershipGuards();
        System.out.println("java-jdbc-smoke: PASS");
    }

    static void provesPostgreSqlUnitOfWorkAndOutbox() throws Exception {
        SimulatedDataSource dataSource = new SimulatedDataSource(JdbcDatabaseDialect.POSTGRESQL);
        JdbcTransactionalEventStore store = new JdbcTransactionalEventStore(
                dataSource, JdbcDatabaseDialect.POSTGRESQL);
        AtomicBoolean postCommitObserved = new AtomicBoolean();
        EventEnvelope first = event(1);

        var outcome = store.execute(transaction -> {
            try (PreparedStatement statement = store.requireCurrentConnection()
                    .prepareStatement("/*inx:business-insert*/")) {
                statement.executeUpdate();
            }
            transaction.append(first);
            transaction.afterCommit(() -> postCommitObserved.set(dataSource.businessWrites() == 1));
            return "committed";
        });
        require("committed".equals(outcome.value()), "unit of work did not return its value");
        require(postCommitObserved.get(), "post-commit action ran before durable commit");
        require(dataSource.outboxCount() == 1 && dataSource.businessWrites() == 1,
                "business and outbox writes were not committed atomically");

        try {
            store.execute(transaction -> {
                try (PreparedStatement statement = store.requireCurrentConnection()
                        .prepareStatement("/*inx:business-insert*/")) {
                    statement.executeUpdate();
                }
                transaction.append(event(2));
                throw new IllegalStateException("business failure");
            });
            throw new AssertionError("failed unit of work unexpectedly committed");
        } catch (TransactionExecutionException expected) {
            require(dataSource.outboxCount() == 1 && dataSource.businessWrites() == 1,
                    "rollback leaked business or outbox changes");
        }

        var claimed = store.claimBatch("worker-pg", 10, NOW.plusSeconds(5), Duration.ofSeconds(30));
        require(claimed.size() == 1 && claimed.getFirst().attempts() == 1,
                "PostgreSQL UPDATE RETURNING claim did not lease the event");
        var retryPolicy = new ExponentialBackoffPolicy(
                2, Duration.ofSeconds(2), Duration.ofSeconds(8), 0.0, () -> 0.0);
        require(store.markFailed(first.eventId(), "worker-pg", NOW.plusSeconds(6), retryPolicy,
                        new IllegalStateException("offline")) == OutboxStatus.PENDING,
                "first failure did not schedule a retry");
        require(store.claimBatch("worker-pg", 10, NOW.plusSeconds(7), Duration.ofSeconds(30)).isEmpty(),
                "event became available before its retry delay");
        require(store.claimBatch("worker-pg", 10, NOW.plusSeconds(8), Duration.ofSeconds(30)).size() == 1,
                "event was not reclaimable after retry delay");
        store.markPublished(first.eventId(), "worker-pg", NOW.plusSeconds(9));
        require(dataSource.status(first.eventId()) == OutboxStatus.PUBLISHED,
                "published state was not persisted");
    }

    static void provesOracleClaimsAndInboxDeduplication() {
        SimulatedDataSource dataSource = new SimulatedDataSource(JdbcDatabaseDialect.ORACLE);
        JdbcTransactionalEventStore store = new JdbcTransactionalEventStore(
                dataSource, JdbcDatabaseDialect.ORACLE);
        store.execute(transaction -> {
            transaction.append(event(10));
            transaction.append(event(11));
            return null;
        });
        var claimed = store.claimBatch("worker-oracle", 1, NOW.plusSeconds(20), Duration.ofSeconds(5));
        require(claimed.size() == 1 && claimed.getFirst().status() == OutboxStatus.IN_FLIGHT,
                "Oracle SELECT FOR UPDATE SKIP LOCKED claim failed");
        var oneAttempt = new ExponentialBackoffPolicy(
                1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.0, () -> 0.0);
        require(store.markFailed(claimed.getFirst().event().eventId(), "worker-oracle",
                        NOW.plusSeconds(21), oneAttempt, new SQLException("transport"))
                        == OutboxStatus.DEAD_LETTER,
                "Oracle failure did not move the exhausted event to dead letter");

        InboxProcessor processor = new InboxProcessor(
                store, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        EventEnvelope inbound = event(20);
        var first = processor.process("core.jdbc-smoke", inbound, (event, transaction) ->
                transaction.append(event(21)));
        var duplicate = processor.process("core.jdbc-smoke", inbound, (event, transaction) -> {
            throw new AssertionError("duplicate handler must not execute");
        });
        require(first.value() == InboxProcessingResult.PROCESSED,
                "first inbox delivery was not processed");
        require(duplicate.value() == InboxProcessingResult.DUPLICATE,
                "committed Oracle inbox delivery was not deduplicated");
        require(dataSource.inboxStatus("core.jdbc-smoke", inbound.eventId()).equals("COMPLETED"),
                "inbox reservation was not completed atomically");
    }

    static void provesConfigurationAndOwnershipGuards() {
        SimulatedDataSource dataSource = new SimulatedDataSource(JdbcDatabaseDialect.POSTGRESQL);
        JdbcTransactionalEventStore store = new JdbcTransactionalEventStore(
                dataSource, JdbcDatabaseDialect.POSTGRESQL);
        expect(IllegalArgumentException.class, () -> new JdbcTransactionalEventStore(
                dataSource, JdbcDatabaseDialect.POSTGRESQL, Connection.TRANSACTION_NONE));
        expect(IllegalStateException.class, store::requireCurrentConnection);
        expect(TransactionExecutionException.class, () -> store.execute(outer ->
                store.execute(inner -> null)));
        expect(IllegalArgumentException.class,
                () -> store.claimBatch("worker", 0, NOW, Duration.ofSeconds(1)));

        EventEnvelope event = event(30);
        store.execute(transaction -> { transaction.append(event); return null; });
        store.claimBatch("owner", 1, NOW.plusSeconds(40), Duration.ofSeconds(10));
        expect(IllegalStateException.class,
                () -> store.markPublished(event.eventId(), "intruder", NOW.plusSeconds(41)));
        expect(IllegalArgumentException.class,
                () -> store.markPublished(event(999).eventId(), "owner", NOW.plusSeconds(41)));
    }

    private static EventEnvelope event(int sequence) {
        String suffix = "%012d".formatted(sequence);
        return new EventEnvelope(
                id("018bcfe5-6800-7000-8000-" + suffix),
                new EventType("core.asset.created.v1"),
                ContractVersion.parse("1.0.0"),
                NOW.plusMillis(sequence),
                new EventSource("core/server-1"),
                id("018bcfe5-6800-7002-8000-" + suffix),
                null,
                "{\"sequence\":" + sequence + "}");
    }

    private static DomainIdentifier id(String value) {
        return new DomainIdentifier(UUID.fromString(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but received " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    /** Minimal transactional JDBC simulation keyed by the adapter's operation comments. */
    private static final class SimulatedDataSource implements DataSource {
        private final JdbcDatabaseDialect dialect;
        private DatabaseState committed = new DatabaseState();

        private SimulatedDataSource(JdbcDatabaseDialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
        }

        @Override
        public Connection getConnection() {
            return connectionProxy(this, committed.copy());
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        private synchronized DatabaseState snapshot() {
            return committed.copy();
        }

        private synchronized void commit(DatabaseState state) {
            committed = state.copy();
        }

        private synchronized int outboxCount() {
            return committed.outbox.size();
        }

        private synchronized int businessWrites() {
            return committed.businessWrites;
        }

        private synchronized OutboxStatus status(DomainIdentifier eventId) {
            return committed.outbox.get(eventId.toString()).status;
        }

        private synchronized String inboxStatus(String consumer, DomainIdentifier eventId) {
            return committed.inbox.get(consumer + "|" + eventId).status;
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    }

    private static Connection connectionProxy(SimulatedDataSource owner, DatabaseState initial) {
        class ConnectionHandler implements InvocationHandler {
            private DatabaseState state = initial;
            private boolean autoCommit = true;
            private int isolation = Connection.TRANSACTION_READ_COMMITTED;
            private boolean closed;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                return switch (name) {
                    case "prepareStatement" -> preparedStatementProxy(
                            owner.dialect, this, (String) args[0]);
                    case "getAutoCommit" -> autoCommit;
                    case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                    case "getTransactionIsolation" -> isolation;
                    case "setTransactionIsolation" -> { isolation = (int) args[0]; yield null; }
                    case "commit" -> { owner.commit(state); state = owner.snapshot(); yield null; }
                    case "rollback" -> {
                        if (args == null || args.length == 0) state = owner.snapshot();
                        else state = ((SimulatedSavepoint) args[0]).state.copy();
                        yield null;
                    }
                    case "setSavepoint" -> new SimulatedSavepoint(state.copy());
                    case "releaseSavepoint" -> null;
                    case "close" -> { closed = true; yield null; }
                    case "isClosed" -> closed;
                    case "unwrap" -> {
                        Class<?> type = (Class<?>) args[0];
                        if (type.isInstance(proxy)) yield proxy;
                        throw new SQLException("not a wrapper");
                    }
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                    case "toString" -> "SimulatedConnection[" + owner.dialect + "]";
                    default -> defaultValue(method.getReturnType());
                };
            }
        }
        ConnectionHandler handler = new ConnectionHandler();
        return (Connection) Proxy.newProxyInstance(
                JdbcAdapterSmoke.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private static PreparedStatement preparedStatementProxy(
            JdbcDatabaseDialect dialect, InvocationHandler connectionHandler, String sql) {
        class StatementHandler implements InvocationHandler {
            private final Map<Integer, Object> parameters = new HashMap<>();
            private int maxRows;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                    if (name.equals("setMaxRows") || name.equals("setFetchSize")) {
                        if (name.equals("setMaxRows")) maxRows = (int) args[0];
                        return null;
                    }
                    parameters.put(index, name.equals("setNull") ? null : args[1]);
                    return null;
                }
                DatabaseState state = stateOf(connectionHandler);
                return switch (name) {
                    case "setMaxRows" -> { maxRows = (int) args[0]; yield null; }
                    case "setFetchSize", "close", "clearParameters" -> null;
                    case "executeUpdate" -> state.update(dialect, sql, parameters);
                    case "executeQuery" -> state.query(dialect, sql, parameters, maxRows);
                    case "unwrap" -> {
                        Class<?> type = (Class<?>) args[0];
                        if (type.isInstance(proxy)) yield proxy;
                        throw new SQLException("not a wrapper");
                    }
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                    case "toString" -> "SimulatedPreparedStatement";
                    default -> defaultValue(method.getReturnType());
                };
            }
        }
        return (PreparedStatement) Proxy.newProxyInstance(
                JdbcAdapterSmoke.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, new StatementHandler());
    }

    private static DatabaseState stateOf(InvocationHandler handler) throws ReflectiveOperationException {
        var field = handler.getClass().getDeclaredField("state");
        field.setAccessible(true);
        return (DatabaseState) field.get(handler);
    }

    private static final class SimulatedSavepoint implements Savepoint {
        private final DatabaseState state;
        private SimulatedSavepoint(DatabaseState state) { this.state = state; }
        @Override public int getSavepointId() { return 1; }
        @Override public String getSavepointName() { return "infranexum"; }
    }

    private static final class DatabaseState {
        private final Map<String, OutboxRow> outbox = new LinkedHashMap<>();
        private final Map<String, InboxRow> inbox = new LinkedHashMap<>();
        private int businessWrites;

        private DatabaseState copy() {
            DatabaseState copy = new DatabaseState();
            outbox.forEach((key, value) -> copy.outbox.put(key, value.copy()));
            inbox.forEach((key, value) -> copy.inbox.put(key, value.copy()));
            copy.businessWrites = businessWrites;
            return copy;
        }

        private int update(
                JdbcDatabaseDialect dialect, String sql, Map<Integer, Object> parameters) throws SQLException {
            if (sql.contains("inx:business-insert")) {
                businessWrites++;
                return 1;
            }
            if (sql.contains("inx:outbox-insert")) {
                String id = identifier(parameters.get(1));
                if (outbox.containsKey(id)) throw unique(dialect);
                OutboxRow row = new OutboxRow();
                row.eventId = id;
                row.eventType = (String) parameters.get(2);
                row.schemaVersion = (String) parameters.get(3);
                row.occurredAt = instant(parameters.get(4));
                row.source = (String) parameters.get(5);
                row.correlationId = identifier(parameters.get(6));
                row.causationId = parameters.get(7) == null ? null : identifier(parameters.get(7));
                row.payload = (String) parameters.get(8);
                row.availableAt = instant(parameters.get(9));
                outbox.put(id, row);
                return 1;
            }
            if (sql.contains("inx:inbox-reserve")) {
                String key = parameters.get(1) + "|" + identifier(parameters.get(2));
                if (inbox.containsKey(key)) {
                    if (dialect == JdbcDatabaseDialect.POSTGRESQL) return 0;
                    throw unique(dialect);
                }
                InboxRow row = new InboxRow();
                row.consumer = (String) parameters.get(1);
                row.eventId = identifier(parameters.get(2));
                row.eventType = (String) parameters.get(3);
                row.payloadHash = (String) parameters.get(4);
                row.receivedAt = instant(parameters.get(5));
                row.status = "PROCESSING";
                inbox.put(key, row);
                return 1;
            }
            if (sql.contains("inx:inbox-complete")) {
                String key = parameters.get(2) + "|" + identifier(parameters.get(3));
                InboxRow row = inbox.get(key);
                if (row == null || !row.status.equals("PROCESSING")) return 0;
                row.completedAt = instant(parameters.get(1));
                row.status = "COMPLETED";
                return 1;
            }
            if (sql.contains("inx:outbox-claim-oracle")) {
                OutboxRow row = outbox.get(identifier(parameters.get(4)));
                if (row == null) return 0;
                row.status = OutboxStatus.IN_FLIGHT;
                row.attempts++;
                row.leaseOwner = (String) parameters.get(1);
                row.leaseUntil = instant(parameters.get(2));
                return 1;
            }
            if (sql.contains("inx:outbox-publish")) {
                OutboxRow row = outbox.get(identifier(parameters.get(3)));
                if (row == null || row.status != OutboxStatus.IN_FLIGHT
                        || !Objects.equals(row.leaseOwner, parameters.get(4))) return 0;
                row.status = OutboxStatus.PUBLISHED;
                row.publishedAt = instant(parameters.get(1));
                row.leaseOwner = null;
                row.leaseUntil = null;
                row.lastFailure = null;
                return 1;
            }
            if (sql.contains("inx:outbox-fail")) {
                OutboxRow row = outbox.get(identifier(parameters.get(5)));
                if (row == null || row.status != OutboxStatus.IN_FLIGHT
                        || !Objects.equals(row.leaseOwner, parameters.get(6))) return 0;
                row.status = OutboxStatus.valueOf((String) parameters.get(1));
                row.availableAt = instant(parameters.get(2));
                row.lastFailure = (String) parameters.get(3);
                row.leaseOwner = null;
                row.leaseUntil = null;
                return 1;
            }
            throw new SQLException("unsupported simulated update");
        }

        private ResultSet query(
                JdbcDatabaseDialect dialect, String sql, Map<Integer, Object> parameters, int maxRows)
                throws SQLException {
            if (sql.contains("inx:outbox-claim-postgresql")) {
                Instant now = instant(parameters.get(1));
                int limit = (int) parameters.get(3);
                String worker = (String) parameters.get(4);
                Instant leaseUntil = instant(parameters.get(5));
                List<OutboxRow> selected = candidates(now, limit);
                for (OutboxRow row : selected) {
                    row.status = OutboxStatus.IN_FLIGHT;
                    row.attempts++;
                    row.leaseOwner = worker;
                    row.leaseUntil = leaseUntil;
                }
                return outboxRows(dialect, selected);
            }
            if (sql.contains("inx:outbox-select-oracle")) {
                int limit = maxRows == 0 ? Integer.MAX_VALUE : maxRows;
                return outboxRows(dialect, candidates(instant(parameters.get(1)), limit));
            }
            if (sql.contains("inx:outbox-state")) {
                OutboxRow row = outbox.get(identifier(parameters.get(1)));
                if (row == null) return rows(List.of(), "status", "attempts", "lease_owner");
                return rows(List.of(Map.of(
                        "status", row.status.name(),
                        "attempts", row.attempts,
                        "lease_owner", Objects.requireNonNullElse(row.leaseOwner, ""))),
                        "status", "attempts", "lease_owner");
            }
            if (sql.contains("inx:inbox-status")) {
                InboxRow row = inbox.get(parameters.get(1) + "|" + identifier(parameters.get(2)));
                if (row == null) return rows(List.of(), "status");
                return rows(List.of(Map.of("status", row.status)), "status");
            }
            throw new SQLException("unsupported simulated query");
        }

        private List<OutboxRow> candidates(Instant now, int limit) {
            return outbox.values().stream()
                    .filter(row -> (row.status == OutboxStatus.PENDING && !row.availableAt.isAfter(now))
                            || (row.status == OutboxStatus.IN_FLIGHT
                            && row.leaseUntil != null && !row.leaseUntil.isAfter(now)))
                    .sorted(Comparator.comparing((OutboxRow row) -> row.availableAt)
                            .thenComparing(row -> row.occurredAt)
                            .thenComparing(row -> row.eventId))
                    .limit(limit)
                    .toList();
        }
    }

    private static ResultSet outboxRows(JdbcDatabaseDialect dialect, List<OutboxRow> selected)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OutboxRow row : selected) {
            Map<String, Object> values = new HashMap<>();
            values.put("event_id", jdbcIdentifier(dialect, row.eventId));
            values.put("event_type", row.eventType);
            values.put("schema_version", row.schemaVersion);
            values.put("occurred_at", offset(row.occurredAt));
            values.put("event_source", row.source);
            values.put("correlation_id", jdbcIdentifier(dialect, row.correlationId));
            values.put("causation_id", row.causationId == null ? null : jdbcIdentifier(dialect, row.causationId));
            values.put("payload_json", row.payload);
            values.put("status", row.status.name());
            values.put("attempts", row.attempts);
            values.put("available_at", offset(row.availableAt));
            values.put("lease_owner", row.leaseOwner);
            values.put("lease_until", offset(row.leaseUntil));
            values.put("published_at", offset(row.publishedAt));
            values.put("last_failure", row.lastFailure);
            rows.add(values);
        }
        return rows(rows,
                "event_id", "event_type", "schema_version", "occurred_at", "event_source",
                "correlation_id", "causation_id", "payload_json", "status", "attempts",
                "available_at", "lease_owner", "lease_until", "published_at", "last_failure");
    }

    private static CachedRowSet rows(List<Map<String, Object>> rows, String... columns) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.length);
        for (int index = 0; index < columns.length; index++) {
            metadata.setColumnName(index + 1, columns[index]);
            metadata.setColumnLabel(index + 1, columns[index]);
            metadata.setColumnType(index + 1, sqlType(columns[index]));
        }
        rowSet.setMetaData(metadata);
        for (Map<String, Object> row : rows) {
            rowSet.moveToInsertRow();
            for (int index = 0; index < columns.length; index++) {
                rowSet.updateObject(index + 1, row.get(columns[index]));
            }
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
        }
        rowSet.beforeFirst();
        return rowSet;
    }

    private static int sqlType(String column) {
        if (column.equals("attempts")) return Types.INTEGER;
        if (column.endsWith("_at") || column.endsWith("_until")) return Types.TIMESTAMP_WITH_TIMEZONE;
        return Types.VARCHAR;
    }

    private static SQLException unique(JdbcDatabaseDialect dialect) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? new SQLException("duplicate", "23505")
                : new SQLException("duplicate", "23000", 1);
    }

    private static Object jdbcIdentifier(JdbcDatabaseDialect dialect, String value) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? UUID.fromString(value) : value;
    }

    private static String identifier(Object value) {
        return value.toString().strip().toLowerCase();
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
        if (value instanceof Instant timestamp) return timestamp;
        throw new IllegalArgumentException("unsupported simulated instant: " + value);
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
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

    private static final class OutboxRow {
        private String eventId;
        private String eventType;
        private String schemaVersion;
        private Instant occurredAt;
        private String source;
        private String correlationId;
        private String causationId;
        private String payload;
        private OutboxStatus status = OutboxStatus.PENDING;
        private int attempts;
        private Instant availableAt;
        private String leaseOwner;
        private Instant leaseUntil;
        private Instant publishedAt;
        private String lastFailure;

        private OutboxRow copy() {
            OutboxRow copy = new OutboxRow();
            copy.eventId = eventId;
            copy.eventType = eventType;
            copy.schemaVersion = schemaVersion;
            copy.occurredAt = occurredAt;
            copy.source = source;
            copy.correlationId = correlationId;
            copy.causationId = causationId;
            copy.payload = payload;
            copy.status = status;
            copy.attempts = attempts;
            copy.availableAt = availableAt;
            copy.leaseOwner = leaseOwner;
            copy.leaseUntil = leaseUntil;
            copy.publishedAt = publishedAt;
            copy.lastFailure = lastFailure;
            return copy;
        }
    }

    private static final class InboxRow {
        private String consumer;
        private String eventId;
        private String eventType;
        private String payloadHash;
        private Instant receivedAt;
        private Instant completedAt;
        private String status;

        private InboxRow copy() {
            InboxRow copy = new InboxRow();
            copy.consumer = consumer;
            copy.eventId = eventId;
            copy.eventType = eventType;
            copy.payloadHash = payloadHash;
            copy.receivedAt = receivedAt;
            copy.completedAt = completedAt;
            copy.status = status;
            return copy;
        }
    }
}
