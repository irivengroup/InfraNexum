package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventTransaction;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.InboxDecision;
import io.infranexum.core.events.InboxKey;
import io.infranexum.core.events.InboxReservation;
import io.infranexum.core.events.OutboxRecord;
import io.infranexum.core.events.OutboxStatus;
import io.infranexum.core.events.PostCommitAction;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionOutcome;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.core.events.TransactionalWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * JDBC implementation of the transactional event store for PostgreSQL and Oracle.
 *
 * <p>The adapter owns transaction boundaries and exposes the current connection to
 * bounded-context JDBC repositories. Outbox and business writes therefore commit
 * atomically on the same physical connection. Instances are thread-safe; each unit
 * of work is confined to the invoking thread and nested units of work are refused.
 */
public final class JdbcTransactionalEventStore
        implements TransactionalEventStore, JdbcConnectionAccess {
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_FAILURE_LENGTH = 1_024;

    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;
    private final int transactionIsolation;
    private final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();

    public JdbcTransactionalEventStore(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this(dataSource, dialect, Connection.TRANSACTION_READ_COMMITTED);
    }

    public JdbcTransactionalEventStore(
            DataSource dataSource, JdbcDatabaseDialect dialect, int transactionIsolation) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (transactionIsolation == Connection.TRANSACTION_NONE) {
            throw new IllegalArgumentException("transaction isolation must enable transactions");
        }
        this.transactionIsolation = transactionIsolation;
    }

    @Override
    public Connection requireCurrentConnection() {
        Connection connection = currentConnection.get();
        if (connection == null) {
            throw new IllegalStateException("no JDBC unit of work is active on the current thread");
        }
        return connection;
    }

    @Override
    public <T> TransactionOutcome<T> execute(TransactionalWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (currentConnection.get() != null) {
            throw new IllegalStateException("nested JDBC units of work are forbidden");
        }

        T value;
        List<PostCommitAction> actions;
        try (Connection connection = openConnection()) {
            currentConnection.set(connection);
            JdbcEventTransaction transaction = new JdbcEventTransaction(connection);
            try {
                value = work.execute(transaction);
                transaction.validateComplete();
                connection.commit();
                actions = List.copyOf(transaction.postCommitActions);
            } catch (Exception failure) {
                rollback(connection, failure);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new TransactionExecutionException("transactional JDBC work rolled back", failure);
            } finally {
                currentConnection.remove();
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException("execute-unit-of-work", failure);
        }
        return new TransactionOutcome<>(value, runPostCommitActions(actions));
    }

    @Override
    public List<OutboxRecord> claimBatch(
            String workerId, int limit, Instant now, Duration leaseDuration) {
        String worker = requireText(workerId, "workerId", 160);
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant leaseUntil = safeAdd(now, leaseDuration);
        return inOwnTransaction("claim-outbox-batch", connection -> dialect.supportsClaimReturning()
                ? claimReturning(connection, worker, limit, now, leaseUntil)
                : claimSelected(connection, worker, limit, now, leaseUntil));
    }

    @Override
    public void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        String worker = requireText(workerId, "workerId", 160);
        Objects.requireNonNull(publishedAt, "publishedAt");
        inOwnTransaction("mark-outbox-published", connection -> {
            requireLease(connection, eventId, worker);
            try (PreparedStatement statement = connection.prepareStatement(dialect.publishSql())) {
                JdbcTemporal.bindInstant(statement, 1, publishedAt);
                JdbcTemporal.bindInstant(statement, 2, publishedAt);
                dialect.bindIdentifier(statement, 3, eventId);
                statement.setString(4, worker);
                requireSingleUpdate(statement.executeUpdate(), "publish outbox event");
            }
            return null;
        });
    }

    @Override
    public OutboxStatus markFailed(
            DomainIdentifier eventId,
            String workerId,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure) {
        Objects.requireNonNull(eventId, "eventId");
        String worker = requireText(workerId, "workerId", 160);
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(failure, "failure");
        return inOwnTransaction("mark-outbox-failed", connection -> {
            LeaseState lease = requireLease(connection, eventId, worker);
            OutboxStatus status;
            Instant availableAt;
            if (lease.attempts() >= retryPolicy.maximumAttempts()) {
                status = OutboxStatus.DEAD_LETTER;
                availableAt = failedAt;
            } else {
                status = OutboxStatus.PENDING;
                availableAt = safeAdd(failedAt, retryPolicy.delayAfterFailure(lease.attempts()));
            }
            try (PreparedStatement statement = connection.prepareStatement(dialect.failSql())) {
                statement.setString(1, status.name());
                JdbcTemporal.bindInstant(statement, 2, availableAt);
                statement.setString(3, sanitizeFailure(failure));
                JdbcTemporal.bindInstant(statement, 4, failedAt);
                dialect.bindIdentifier(statement, 5, eventId);
                statement.setString(6, worker);
                requireSingleUpdate(statement.executeUpdate(), "fail outbox event");
            }
            return status;
        });
    }

    private Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);
            }
            if (connection.getTransactionIsolation() != transactionIsolation) {
                connection.setTransactionIsolation(transactionIsolation);
            }
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private List<OutboxRecord> claimReturning(
            Connection connection, String worker, int limit, Instant now, Instant leaseUntil)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.claimReturningSql())) {
            JdbcTemporal.bindInstant(statement, 1, now);
            JdbcTemporal.bindInstant(statement, 2, now);
            statement.setInt(3, limit);
            statement.setString(4, worker);
            JdbcTemporal.bindInstant(statement, 5, leaseUntil);
            JdbcTemporal.bindInstant(statement, 6, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readOutboxRecords(resultSet);
            }
        }
    }

    private List<OutboxRecord> claimSelected(
            Connection connection, String worker, int limit, Instant now, Instant leaseUntil)
            throws SQLException {
        List<OutboxRecord> selected;
        try (PreparedStatement statement = connection.prepareStatement(dialect.selectClaimCandidatesSql())) {
            JdbcTemporal.bindInstant(statement, 1, now);
            JdbcTemporal.bindInstant(statement, 2, now);
            statement.setFetchSize(limit);
            statement.setMaxRows(limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                selected = readOutboxRecords(resultSet);
            }
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        List<OutboxRecord> claimed = new ArrayList<>(selected.size());
        try (PreparedStatement statement = connection.prepareStatement(dialect.claimOneSql())) {
            for (OutboxRecord record : selected) {
                statement.setString(1, worker);
                JdbcTemporal.bindInstant(statement, 2, leaseUntil);
                JdbcTemporal.bindInstant(statement, 3, now);
                dialect.bindIdentifier(statement, 4, record.event().eventId());
                requireSingleUpdate(statement.executeUpdate(), "claim Oracle outbox event");
                claimed.add(new OutboxRecord(
                        record.event(),
                        OutboxStatus.IN_FLIGHT,
                        record.attempts() + 1,
                        record.availableAt(),
                        worker,
                        leaseUntil,
                        null,
                        record.lastFailure()));
            }
        }
        return List.copyOf(claimed);
    }

    private LeaseState requireLease(Connection connection, DomainIdentifier eventId, String worker)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.selectLeaseSql())) {
            dialect.bindIdentifier(statement, 1, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("unknown outbox event: " + eventId);
                }
                OutboxStatus status = OutboxStatus.valueOf(resultSet.getString("status"));
                int attempts = resultSet.getInt("attempts");
                String owner = resultSet.getString("lease_owner");
                if (status != OutboxStatus.IN_FLIGHT || !worker.equals(owner)) {
                    throw new IllegalStateException("outbox event is not leased by worker " + worker);
                }
                return new LeaseState(attempts);
            }
        }
    }

    private List<OutboxRecord> readOutboxRecords(ResultSet resultSet) throws SQLException {
        List<OutboxRecord> records = new ArrayList<>();
        while (resultSet.next()) {
            DomainIdentifier causationId = null;
            Object causation = resultSet.getObject("causation_id");
            if (causation != null) {
                causationId = dialect.readIdentifier(resultSet, "causation_id");
            }
            EventEnvelope event = new EventEnvelope(
                    dialect.readIdentifier(resultSet, "event_id"),
                    new EventType(resultSet.getString("event_type")),
                    ContractVersion.parse(resultSet.getString("schema_version")),
                    JdbcTemporal.readRequired(resultSet, "occurred_at"),
                    new EventSource(resultSet.getString("event_source")),
                    dialect.readIdentifier(resultSet, "correlation_id"),
                    causationId,
                    resultSet.getString("payload_json"));
            records.add(new OutboxRecord(
                    event,
                    OutboxStatus.valueOf(resultSet.getString("status")),
                    resultSet.getInt("attempts"),
                    JdbcTemporal.readRequired(resultSet, "available_at"),
                    resultSet.getString("lease_owner"),
                    JdbcTemporal.readNullable(resultSet, "lease_until"),
                    JdbcTemporal.readNullable(resultSet, "published_at"),
                    resultSet.getString("last_failure")));
        }
        return List.copyOf(records);
    }

    private <T> T inOwnTransaction(String operation, SqlWork<T> work) {
        if (currentConnection.get() != null) {
            throw new IllegalStateException(operation + " must not run inside a business unit of work");
        }
        try (Connection connection = openConnection()) {
            try {
                T value = work.execute(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure) {
                    throw new JdbcPersistenceException(operation, sqlFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException(operation, failure);
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(operation + " affected " + count + " rows instead of one");
        }
    }

    private static Instant safeAdd(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (java.time.DateTimeException | ArithmeticException failure) {
            throw new IllegalArgumentException("time calculation overflow", failure);
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String sanitizeFailure(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        String safe = simpleName.isBlank() ? "Failure" : simpleName;
        return safe.substring(0, Math.min(safe.length(), MAX_FAILURE_LENGTH));
    }

    private static List<String> runPostCommitActions(List<PostCommitAction> actions) {
        List<String> failures = new ArrayList<>();
        for (PostCommitAction action : actions) {
            try {
                action.run();
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.add(sanitizeFailure(failure));
            }
        }
        return List.copyOf(failures);
    }

    private final class JdbcEventTransaction implements EventTransaction {
        private final Connection connection;
        private final Map<InboxKey, InboxReservation> acceptedInbox = new HashMap<>();
        private final Set<InboxKey> completedInbox = new HashSet<>();
        private final List<PostCommitAction> postCommitActions = new ArrayList<>();

        private JdbcEventTransaction(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void append(EventEnvelope event) {
            Objects.requireNonNull(event, "event");
            try (PreparedStatement statement = connection.prepareStatement(dialect.insertOutboxSql())) {
                dialect.bindIdentifier(statement, 1, event.eventId());
                statement.setString(2, event.eventType().value());
                statement.setString(3, event.schemaVersion().toString());
                JdbcTemporal.bindInstant(statement, 4, event.occurredAt());
                statement.setString(5, event.source().value());
                dialect.bindIdentifier(statement, 6, event.correlationId());
                dialect.bindNullableIdentifier(statement, 7, event.causationId());
                dialect.bindJson(statement, 8, event.payload());
                JdbcTemporal.bindInstant(statement, 9, event.occurredAt());
                requireSingleUpdate(statement.executeUpdate(), "insert outbox event");
            } catch (SQLException failure) {
                throw new JdbcPersistenceException("append-outbox-event", failure);
            }
        }

        @Override
        public InboxDecision beginInbox(InboxReservation reservation) {
            Objects.requireNonNull(reservation, "reservation");
            InboxKey key = reservation.key();
            if (acceptedInbox.containsKey(key)) {
                throw new IllegalStateException("inbox key already reserved in this transaction: " + key);
            }
            try {
                if (dialect.tryReserveInbox(connection, reservation)) {
                    acceptedInbox.put(key, reservation);
                    return InboxDecision.ACCEPTED;
                }
                String status = readInboxStatus(key);
                if ("COMPLETED".equals(status)) {
                    return InboxDecision.DUPLICATE;
                }
                throw new IllegalStateException("inbox key is already processing: " + key);
            } catch (SQLException failure) {
                throw new JdbcPersistenceException("reserve-inbox-event", failure);
            }
        }

        @Override
        public void completeInbox(InboxKey key, Instant completedAt) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(completedAt, "completedAt");
            InboxReservation reservation = acceptedInbox.get(key);
            if (reservation == null) {
                throw new IllegalStateException("inbox key was not accepted in this transaction: " + key);
            }
            if (!completedInbox.add(key)) {
                throw new IllegalStateException("inbox key already completed in this transaction: " + key);
            }
            if (completedAt.isBefore(reservation.receivedAt())) {
                throw new IllegalArgumentException("completedAt must not precede receivedAt");
            }
            try (PreparedStatement statement = connection.prepareStatement(dialect.completeInboxSql())) {
                JdbcTemporal.bindInstant(statement, 1, completedAt);
                statement.setString(2, key.consumerName());
                dialect.bindIdentifier(statement, 3, key.eventId());
                requireSingleUpdate(statement.executeUpdate(), "complete inbox event");
            } catch (SQLException failure) {
                throw new JdbcPersistenceException("complete-inbox-event", failure);
            }
        }

        @Override
        public void afterCommit(PostCommitAction action) {
            postCommitActions.add(Objects.requireNonNull(action, "action"));
        }

        private String readInboxStatus(InboxKey key) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(dialect.inboxStatusSql())) {
                statement.setString(1, key.consumerName());
                dialect.bindIdentifier(statement, 2, key.eventId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("conflicting inbox key disappeared during reservation");
                    }
                    return resultSet.getString("status");
                }
            }
        }

        private void validateComplete() {
            for (InboxKey key : acceptedInbox.keySet()) {
                if (!completedInbox.contains(key)) {
                    throw new IllegalStateException("accepted inbox key was not completed: " + key);
                }
            }
        }
    }

    private record LeaseState(int attempts) {}

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
