package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.core.workers.CancellationOutcome;
import io.infranexum.core.workers.IdempotencyConflictException;
import io.infranexum.core.workers.RetrySafety;
import io.infranexum.core.workers.TaskCheckpoint;
import io.infranexum.core.workers.TaskId;
import io.infranexum.core.workers.TaskLeaseLostException;
import io.infranexum.core.workers.TaskRecord;
import io.infranexum.core.workers.TaskStatus;
import io.infranexum.core.workers.TaskStore;
import io.infranexum.core.workers.TaskStoreUnavailableException;
import io.infranexum.core.workers.TaskSubmission;
import io.infranexum.core.workers.TaskSubmissionResult;
import io.infranexum.core.workers.TaskType;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Savepoint;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Durable JDBC {@link TaskStore} for PostgreSQL and Oracle.
 *
 * <p>Every state transition owns a short database transaction. Due-task claims use row locks with
 * {@code SKIP LOCKED}; expired-lease recovery uses a bounded optimistic compare-and-set so a
 * recovery sweep never holds a large lock set. Execution mutations are fenced by task id, owner
 * and monotonically increasing lease version. The adapter never retries an {@code AT_MOST_ONCE}
 * task after an expired lease because its external side-effect outcome is unknowable.
 */
public final class JdbcTaskStore implements TaskStore {
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_FAILURE_LENGTH = 1_024;
    private static final int MAX_LEASE_RECOVERY_BATCH = 1_000;

    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;
    private final int transactionIsolation;

    public JdbcTaskStore(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this(dataSource, dialect, Connection.TRANSACTION_READ_COMMITTED);
    }

    public JdbcTaskStore(
            DataSource dataSource, JdbcDatabaseDialect dialect, int transactionIsolation) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (transactionIsolation == Connection.TRANSACTION_NONE) {
            throw new IllegalArgumentException("transaction isolation must enable transactions");
        }
        this.transactionIsolation = transactionIsolation;
    }

    @Override
    public TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            Instant submittedAt) {
        return submit(proposedId, submission, retrySafety, null, submittedAt);
    }

    @Override
    public TaskSubmissionResult submit(
            TaskId proposedId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            DomainIdentifier correlationId,
            Instant submittedAt) {
        Objects.requireNonNull(proposedId, "proposedId");
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(retrySafety, "retrySafety");
        Objects.requireNonNull(submittedAt, "submittedAt");
        return inTransaction("submit task", connection -> {
            SubmissionState existing = findSubmissionByScope(
                    connection, submission.type(), submission.idempotencyKey());
            if (existing != null) {
                return replay(existing, submission, retrySafety);
            }

            Savepoint savepoint = connection.setSavepoint();
            try {
                insertTask(connection, proposedId, submission, retrySafety, correlationId, submittedAt);
                insertParameters(connection, proposedId, submission.parameters());
                connection.releaseSavepoint(savepoint);
                return new TaskSubmissionResult(proposedId, true);
            } catch (SQLException failure) {
                restoreSavepoint(connection, savepoint, failure);
                if (!dialect.isUniqueViolation(failure)) {
                    throw failure;
                }
                SubmissionState raced = findSubmissionByScope(
                        connection, submission.type(), submission.idempotencyKey());
                if (raced != null) {
                    return replay(raced, submission, retrySafety);
                }
                if (taskExists(connection, proposedId)) {
                    throw new IllegalArgumentException(
                            "task identifier already exists: " + proposedId, failure);
                }
                throw failure;
            }
        });
    }

    @Override
    public List<TaskRecord> claimBatch(
            String workerId,
            int limit,
            Instant now,
            Duration leaseDuration,
            RetryPolicy retryPolicy) {
        String worker = requireText(workerId, "workerId", 160);
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        return inTransaction("claim tasks", connection -> {
            recoverExpiredLeases(connection, now, retryPolicy);
            List<TaskId> candidates = selectDueTaskIds(connection, now, limit);
            if (candidates.isEmpty()) {
                return List.of();
            }
            Instant leaseUntil = safeAdd(now, lease);
            try (PreparedStatement statement = connection.prepareStatement(claimSql())) {
                for (TaskId taskId : candidates) {
                    statement.setString(1, worker);
                    JdbcTemporal.bindInstant(statement, 2, leaseUntil);
                    JdbcTemporal.bindInstant(statement, 3, now);
                    dialect.bindIdentifier(statement, 4, taskId.value());
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                for (int count : counts) {
                    if (count != 1) {
                        throw new IllegalStateException(
                                "leased task changed while holding its claim row lock");
                    }
                }
            }
            return readTasks(connection, candidates);
        });
    }

    @Override
    public void renewLease(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant now,
            Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        inTransaction("renew task lease", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(renewLeaseSql())) {
                JdbcTemporal.bindInstant(statement, 1, safeAdd(now, lease));
                JdbcTemporal.bindInstant(statement, 2, now);
                bindLeaseIdentity(statement, 3, taskId, workerId, leaseVersion);
                if (statement.executeUpdate() != 1) {
                    throwLeaseFailure(connection, taskId, workerId, leaseVersion);
                }
            }
            return null;
        });
    }

    @Override
    public TaskCheckpoint saveCheckpoint(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            String token,
            Instant now,
            Duration leaseDuration) {
        Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        // Constructing validates the token before a transaction is opened; the sequence value is replaced below.
        new TaskCheckpoint(1, token, now);
        return inTransaction("save task checkpoint", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(saveCheckpointSql())) {
                bindLargeText(statement, 1, token);
                JdbcTemporal.bindInstant(statement, 2, now);
                JdbcTemporal.bindInstant(statement, 3, safeAdd(now, lease));
                JdbcTemporal.bindInstant(statement, 4, now);
                bindLeaseIdentity(statement, 5, taskId, workerId, leaseVersion);
                if (statement.executeUpdate() != 1) {
                    LeaseState state = selectLeaseState(connection, taskId, true);
                    requireLease(state, taskId, workerId, leaseVersion);
                    if (state.cancellationRequested()) {
                        throw new IllegalStateException(
                                "cannot checkpoint after cancellation was requested");
                    }
                    throw new TaskLeaseLostException(
                            "task lease is no longer owned by " + workerId.strip());
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(selectCheckpointSql())) {
                dialect.bindIdentifier(statement, 1, requireTaskId(taskId).value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("checkpointed task disappeared: " + taskId);
                    }
                    return readCheckpoint(resultSet);
                }
            }
        });
    }

    @Override
    public void markSucceeded(
            TaskId taskId, String workerId, long leaseVersion, Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        mutateLeasedTask(
                "mark task succeeded",
                completeSql("SUCCEEDED", true),
                taskId,
                workerId,
                leaseVersion,
                completedAt,
                null);
    }

    @Override
    public TaskStatus markFailed(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure) {
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(failure, "failure");
        return inTransaction("mark task failed", connection -> {
            LeaseState state = selectLeaseState(connection, taskId, true);
            requireLease(state, taskId, workerId, leaseVersion);
            TaskStatus target;
            Instant availableAt = state.availableAt();
            if (state.cancellationRequested()) {
                target = TaskStatus.CANCELLED;
            } else if (state.retrySafety() == RetrySafety.RETRY_SAFE
                    && state.attempts() < retryPolicy.maximumAttempts()) {
                target = TaskStatus.PENDING;
                availableAt = safeAdd(failedAt, retryPolicy.delayAfterFailure(state.attempts()));
            } else {
                target = TaskStatus.FAILED;
            }
            updateFailure(
                    connection,
                    taskId,
                    workerId,
                    leaseVersion,
                    target,
                    availableAt,
                    failedAt,
                    sanitizeFailure(failure));
            return target;
        });
    }

    @Override
    public void markTerminalFailure(
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant failedAt,
            Throwable failure) {
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(failure, "failure");
        inTransaction("mark terminal task failure", connection -> {
            LeaseState state = selectLeaseState(connection, taskId, true);
            requireLease(state, taskId, workerId, leaseVersion);
            TaskStatus target = state.cancellationRequested() ? TaskStatus.CANCELLED : TaskStatus.FAILED;
            updateFailure(
                    connection,
                    taskId,
                    workerId,
                    leaseVersion,
                    target,
                    state.availableAt(),
                    failedAt,
                    sanitizeFailure(failure));
            return null;
        });
    }

    @Override
    public void markCancelled(
            TaskId taskId, String workerId, long leaseVersion, Instant cancelledAt) {
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        mutateLeasedTask(
                "mark task cancelled",
                completeSql("CANCELLED", false),
                taskId,
                workerId,
                leaseVersion,
                cancelledAt,
                "Y");
    }

    @Override
    public CancellationOutcome requestCancellation(TaskId taskId, Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt");
        return inTransaction("request task cancellation", connection -> {
            TaskId id = requireTaskId(taskId);
            try (PreparedStatement statement = connection.prepareStatement(selectCancellationStateSql())) {
                dialect.bindIdentifier(statement, 1, id.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return CancellationOutcome.NOT_FOUND;
                    }
                    TaskStatus status = TaskStatus.valueOf(resultSet.getString("status"));
                    boolean requested = readBooleanMarker(resultSet, "cancellation_requested");
                    if (status.terminal()) {
                        return CancellationOutcome.ALREADY_TERMINAL;
                    }
                    if (requested) {
                        return CancellationOutcome.ALREADY_REQUESTED;
                    }
                    String target = status == TaskStatus.PENDING ? "CANCELLED" : "RUNNING";
                    try (PreparedStatement update = connection.prepareStatement(requestCancellationSql())) {
                        update.setString(1, target);
                        JdbcTemporal.bindInstant(update, 2, requestedAt);
                        dialect.bindIdentifier(update, 3, id.value());
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException(
                                    "task cancellation state changed while holding its row lock");
                        }
                    }
                    return CancellationOutcome.REQUESTED;
                }
            }
        });
    }

    @Override
    public Optional<TaskRecord> find(TaskId taskId) {
        TaskId id = requireTaskId(taskId);
        return inTransaction("find task", connection -> {
            List<TaskRecord> records = readTasks(connection, List.of(id));
            return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
        });
    }

    private TaskSubmissionResult replay(
            SubmissionState existing, TaskSubmission submission, RetrySafety retrySafety) {
        if (!existing.matches(submission, retrySafety)) {
            throw new IdempotencyConflictException(
                    "idempotency key was already used with different task semantics: "
                            + submission.idempotencyKey());
        }
        return new TaskSubmissionResult(existing.taskId(), false);
    }

    private void insertTask(
            Connection connection,
            TaskId taskId,
            TaskSubmission submission,
            RetrySafety retrySafety,
            DomainIdentifier correlationId,
            Instant submittedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertTaskSql())) {
            dialect.bindIdentifier(statement, 1, taskId.value());
            statement.setString(2, submission.type().value());
            statement.setString(3, submission.idempotencyKey());
            statement.setString(4, retrySafety.name());
            dialect.bindNullableIdentifier(statement, 5, correlationId);
            JdbcTemporal.bindInstant(statement, 6, submission.notBefore());
            JdbcTemporal.bindInstant(statement, 7, submission.notBefore());
            JdbcTemporal.bindInstant(statement, 8, submittedAt);
            JdbcTemporal.bindInstant(statement, 9, submittedAt);
            requireSingleUpdate(statement.executeUpdate(), "insert task");
        }
    }

    private void insertParameters(
            Connection connection, TaskId taskId, Map<String, String> parameters) throws SQLException {
        if (parameters.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(insertParameterSql())) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                dialect.bindIdentifier(statement, 1, taskId.value());
                statement.setString(2, entry.getKey());
                bindLargeText(statement, 3, entry.getValue());
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count != 1) {
                    throw new SQLException("insert task parameter affected " + count + " rows");
                }
            }
        }
    }

    private SubmissionState findSubmissionByScope(
            Connection connection, TaskType type, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(selectSubmissionSql())) {
            statement.setString(1, type.value());
            statement.setString(2, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                TaskId taskId = new TaskId(dialect.readIdentifier(resultSet, "task_id"));
                return new SubmissionState(
                        taskId,
                        new TaskType(resultSet.getString("task_type")),
                        resultSet.getString("idempotency_key"),
                        readParameters(connection, taskId),
                        RetrySafety.valueOf(resultSet.getString("retry_safety")),
                        JdbcTemporal.readRequired(resultSet, "requested_not_before"));
            }
        }
    }

    private boolean taskExists(Connection connection, TaskId taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(taskExistsSql())) {
            dialect.bindIdentifier(statement, 1, taskId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void recoverExpiredLeases(
            Connection connection, Instant now, RetryPolicy retryPolicy) throws SQLException {
        List<ExpiredLease> expired = selectExpiredLeases(connection, now);
        for (ExpiredLease lease : expired) {
            TaskStatus target;
            Instant availableAt = lease.availableAt();
            String failure;
            if (lease.cancellationRequested()) {
                target = TaskStatus.CANCELLED;
                failure = "execution lease expired after cancellation request";
            } else if (lease.retrySafety() == RetrySafety.RETRY_SAFE
                    && lease.attempts() < retryPolicy.maximumAttempts()) {
                target = TaskStatus.PENDING;
                availableAt = safeAdd(now, retryPolicy.delayAfterFailure(lease.attempts()));
                failure = "execution lease expired; retry-safe task released for recovery";
            } else {
                target = TaskStatus.FAILED;
                failure = lease.retrySafety() == RetrySafety.AT_MOST_ONCE
                        ? "execution lease expired; outcome unknown; automatic retry forbidden"
                        : "execution lease expired at maximum retry attempts";
            }
            try (PreparedStatement statement = connection.prepareStatement(recoverLeaseSql())) {
                statement.setString(1, target.name());
                JdbcTemporal.bindInstant(statement, 2, availableAt);
                statement.setString(3, failure);
                JdbcTemporal.bindInstant(statement, 4, now);
                dialect.bindIdentifier(statement, 5, lease.taskId().value());
                statement.setLong(6, lease.leaseVersion());
                JdbcTemporal.bindInstant(statement, 7, now);
                statement.setString(8, lease.cancellationRequested() ? "Y" : "N");
                int updated = statement.executeUpdate();
                if (updated > 1) {
                    throw new IllegalStateException(
                            "recover expired task lease affected more than one row");
                }
                // A zero-row update means another transaction changed the lease/cancellation state first.
            }
        }
    }

    private List<ExpiredLease> selectExpiredLeases(Connection connection, Instant now)
            throws SQLException {
        List<ExpiredLease> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(selectExpiredLeasesSql())) {
            JdbcTemporal.bindInstant(statement, 1, now);
            statement.setInt(2, MAX_LEASE_RECOVERY_BATCH);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new ExpiredLease(
                            new TaskId(dialect.readIdentifier(resultSet, "task_id")),
                            RetrySafety.valueOf(resultSet.getString("retry_safety")),
                            resultSet.getInt("attempts"),
                            JdbcTemporal.readRequired(resultSet, "available_at"),
                            resultSet.getLong("lease_version"),
                            readBooleanMarker(resultSet, "cancellation_requested")));
                }
            }
        }
        return result;
    }

    private List<TaskId> selectDueTaskIds(Connection connection, Instant now, int limit)
            throws SQLException {
        List<TaskId> result = new ArrayList<>(limit);
        try (PreparedStatement statement = connection.prepareStatement(selectDueTasksSql())) {
            JdbcTemporal.bindInstant(statement, 1, now);
            if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
                statement.setInt(2, limit);
            } else {
                statement.setMaxRows(limit);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new TaskId(dialect.readIdentifier(resultSet, "task_id")));
                }
            }
        }
        return result;
    }

    private List<TaskRecord> readTasks(Connection connection, List<TaskId> taskIds) throws SQLException {
        // All callers enforce a non-empty identifier set before reaching this method.
        Map<TaskId, TaskRow> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(selectTasksSql(taskIds.size()))) {
            bindTaskIds(statement, taskIds);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TaskRow row = readTaskRow(resultSet);
                    rows.put(row.taskId(), row);
                }
            }
        }
        Map<TaskId, Map<String, String>> parameters = readParameters(connection, taskIds);
        List<TaskRecord> records = new ArrayList<>(taskIds.size());
        for (TaskId taskId : taskIds) {
            TaskRow row = rows.get(taskId);
            if (row != null) {
                records.add(row.toRecord(parameters.getOrDefault(taskId, Map.of())));
            }
        }
        return List.copyOf(records);
    }

    private Map<String, String> readParameters(Connection connection, TaskId taskId)
            throws SQLException {
        return readParameters(connection, List.of(taskId)).getOrDefault(taskId, Map.of());
    }

    private Map<TaskId, Map<String, String>> readParameters(
            Connection connection, List<TaskId> taskIds) throws SQLException {
        Map<TaskId, Map<String, String>> parameters = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(selectParametersSql(taskIds.size()))) {
            bindTaskIds(statement, taskIds);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TaskId taskId = new TaskId(dialect.readIdentifier(resultSet, "task_id"));
                    parameters.computeIfAbsent(taskId, ignored -> new LinkedHashMap<>())
                            .put(resultSet.getString("parameter_key"), resultSet.getString("parameter_value"));
                }
            }
        }
        return parameters;
    }

    private void bindTaskIds(PreparedStatement statement, List<TaskId> taskIds) throws SQLException {
        for (int index = 0; index < taskIds.size(); index++) {
            dialect.bindIdentifier(statement, index + 1, taskIds.get(index).value());
        }
    }

    private TaskRow readTaskRow(ResultSet resultSet) throws SQLException {
        TaskCheckpoint checkpoint = null;
        Object sequence = resultSet.getObject("checkpoint_sequence");
        if (sequence != null) {
            if (!(sequence instanceof Number number)) {
                throw new SQLException("unsupported checkpoint sequence representation");
            }
            checkpoint = new TaskCheckpoint(
                    number.longValue(),
                    resultSet.getString("checkpoint_token"),
                    JdbcTemporal.readRequired(resultSet, "checkpoint_at"));
        }
        return new TaskRow(
                new TaskId(dialect.readIdentifier(resultSet, "task_id")),
                new TaskType(resultSet.getString("task_type")),
                resultSet.getString("idempotency_key"),
                resultSet.getObject("correlation_id") == null
                        ? null
                        : dialect.readIdentifier(resultSet, "correlation_id"),
                RetrySafety.valueOf(resultSet.getString("retry_safety")),
                TaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempts"),
                JdbcTemporal.readRequired(resultSet, "available_at"),
                resultSet.getString("lease_owner"),
                resultSet.getLong("lease_version"),
                JdbcTemporal.readNullable(resultSet, "lease_until"),
                checkpoint,
                readBooleanMarker(resultSet, "cancellation_requested"),
                resultSet.getString("last_failure"),
                JdbcTemporal.readRequired(resultSet, "created_at"),
                JdbcTemporal.readRequired(resultSet, "updated_at"));
    }

    private LeaseState selectLeaseState(Connection connection, TaskId taskId, boolean forUpdate)
            throws SQLException {
        TaskId id = requireTaskId(taskId);
        try (PreparedStatement statement = connection.prepareStatement(selectLeaseStateSql(forUpdate))) {
            dialect.bindIdentifier(statement, 1, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new LeaseState(
                        TaskStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempts"),
                        RetrySafety.valueOf(resultSet.getString("retry_safety")),
                        JdbcTemporal.readRequired(resultSet, "available_at"),
                        resultSet.getString("lease_owner"),
                        resultSet.getLong("lease_version"),
                        readBooleanMarker(resultSet, "cancellation_requested"));
            }
        }
    }

    private void updateFailure(
            Connection connection,
            TaskId taskId,
            String workerId,
            long leaseVersion,
            TaskStatus target,
            Instant availableAt,
            Instant failedAt,
            String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(failSql())) {
            statement.setString(1, target.name());
            JdbcTemporal.bindInstant(statement, 2, availableAt);
            statement.setString(3, failure);
            JdbcTemporal.bindInstant(statement, 4, failedAt);
            bindLeaseIdentity(statement, 5, taskId, workerId, leaseVersion);
            if (statement.executeUpdate() != 1) {
                throwLeaseFailure(connection, taskId, workerId, leaseVersion);
            }
        }
    }

    private void mutateLeasedTask(
            String operation,
            String sql,
            TaskId taskId,
            String workerId,
            long leaseVersion,
            Instant timestamp,
            String cancellationMarker) {
        inTransaction(operation, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (cancellationMarker != null) {
                    statement.setString(index++, cancellationMarker);
                }
                JdbcTemporal.bindInstant(statement, index++, timestamp);
                bindLeaseIdentity(statement, index, taskId, workerId, leaseVersion);
                if (statement.executeUpdate() != 1) {
                    throwLeaseFailure(connection, taskId, workerId, leaseVersion);
                }
            }
            return null;
        });
    }

    private void bindLargeText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (dialect == JdbcDatabaseDialect.ORACLE) {
            statement.setCharacterStream(index, new StringReader(value), value.length());
        } else {
            statement.setString(index, value);
        }
    }

    private void bindLeaseIdentity(
            PreparedStatement statement,
            int firstIndex,
            TaskId taskId,
            String workerId,
            long leaseVersion) throws SQLException {
        TaskId id = requireTaskId(taskId);
        String worker = requireText(workerId, "workerId", 160);
        if (leaseVersion < 1) {
            throw new IllegalArgumentException("leaseVersion must be positive");
        }
        dialect.bindIdentifier(statement, firstIndex, id.value());
        statement.setString(firstIndex + 1, worker);
        statement.setLong(firstIndex + 2, leaseVersion);
    }

    private void throwLeaseFailure(
            Connection connection, TaskId taskId, String workerId, long leaseVersion) throws SQLException {
        LeaseState state = selectLeaseState(connection, requireTaskId(taskId), false);
        requireLease(state, taskId, workerId, leaseVersion);
        throw new TaskLeaseLostException("task lease mutation was rejected for " + workerId.strip());
    }

    private static void requireLease(
            LeaseState state, TaskId taskId, String workerId, long leaseVersion) {
        TaskId id = requireTaskId(taskId);
        String worker = requireText(workerId, "workerId", 160);
        if (leaseVersion < 1) {
            throw new IllegalArgumentException("leaseVersion must be positive");
        }
        if (state == null) {
            throw new IllegalArgumentException("unknown task: " + id);
        }
        if (state.status() != TaskStatus.RUNNING
                || !worker.equals(state.leaseOwner())
                || state.leaseVersion() != leaseVersion) {
            throw new TaskLeaseLostException("task lease is no longer owned by " + worker);
        }
    }

    private TaskCheckpoint readCheckpoint(ResultSet resultSet) throws SQLException {
        Object value = resultSet.getObject("checkpoint_sequence");
        if (!(value instanceof Number sequence)) {
            throw new SQLException("checkpoint sequence is missing after checkpoint update");
        }
        return new TaskCheckpoint(
                sequence.longValue(),
                resultSet.getString("checkpoint_token"),
                JdbcTemporal.readRequired(resultSet, "checkpoint_at"));
    }

    private <T> T inTransaction(String operation, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(transactionIsolation);
            try {
                T value = work.execute(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure) {
                    throw persistenceFailure(operation, sqlFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw persistenceFailure(operation, failure);
        }
    }

    private static RuntimeException persistenceFailure(String operation, SQLException failure) {
        if (isTransientConnectivityFailure(failure)) {
            return new TaskStoreUnavailableException(failure);
        }
        return new JdbcPersistenceException(operation, failure);
    }

    private static boolean isTransientConnectivityFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLTransientConnectionException || current instanceof SQLRecoverableException) {
                return true;
            }
            if (current instanceof SQLException sqlFailure) {
                for (SQLException candidate = sqlFailure; candidate != null; candidate = candidate.getNextException()) {
                    String state = candidate.getSQLState();
                    if (state != null && (state.startsWith("08")
                            || state.equals("57P01")
                            || state.equals("57P02")
                            || state.equals("57P03"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void restoreSavepoint(
            Connection connection, Savepoint savepoint, SQLException original) throws SQLException {
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException restoreFailure) {
            original.addSuppressed(restoreFailure);
            throw original;
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private String taskTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_core.worker_task"
                : "INFRANEXUM_CORE_WORKER_TASK";
    }

    private String parameterTable() {
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "infranexum_core.worker_task_parameter"
                : "INFRANEXUM_CORE_WORKER_TASK_PARAMETER";
    }

    private String insertTaskSql() {
        return "/*inx:task-insert*/ INSERT INTO " + taskTable() + " ("
                + "task_id, task_type, idempotency_key, retry_safety, correlation_id, status, attempts, "
                + "requested_not_before, available_at, lease_owner, lease_version, lease_until, "
                + "checkpoint_sequence, checkpoint_token, checkpoint_at, cancellation_requested, "
                + "last_failure, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, NULL, 0, NULL, NULL, NULL, NULL, 'N', NULL, ?, ?)";
    }

    private String insertParameterSql() {
        return "/*inx:task-parameter-insert*/ INSERT INTO " + parameterTable()
                + " (task_id, parameter_key, parameter_value) VALUES (?, ?, ?)";
    }

    private String selectSubmissionSql() {
        return "/*inx:task-idempotency*/ SELECT task_id, task_type, idempotency_key, retry_safety, "
                + "requested_not_before FROM " + taskTable()
                + " WHERE task_type = ? AND idempotency_key = ?";
    }

    private String taskExistsSql() {
        return "/*inx:task-exists*/ SELECT task_id FROM " + taskTable() + " WHERE task_id = ?";
    }

    private String selectExpiredLeasesSql() {
        String columns = "task_id, retry_safety, attempts, available_at, lease_version, cancellation_requested";
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
            return "/*inx:task-expired*/ SELECT " + columns + " FROM " + taskTable()
                    + " WHERE status = 'RUNNING' AND lease_until <= ? "
                    + "ORDER BY lease_until, created_at, task_id LIMIT ?";
        }
        return "/*inx:task-expired*/ SELECT " + columns + " FROM (SELECT " + columns + ", lease_until, created_at "
                + "FROM " + taskTable() + " WHERE status = 'RUNNING' AND lease_until <= ? "
                + "ORDER BY lease_until, created_at, task_id) WHERE ROWNUM <= ?";
    }

    private String recoverLeaseSql() {
        return "/*inx:task-recover*/ UPDATE " + taskTable()
                + " SET status = ?, available_at = ?, lease_owner = NULL, lease_until = NULL, "
                + "last_failure = ?, updated_at = ? WHERE task_id = ? AND status = 'RUNNING' "
                + "AND lease_version = ? AND lease_until <= ? AND cancellation_requested = ?";
    }

    private String selectDueTasksSql() {
        String base = "/*inx:task-due*/ SELECT task_id FROM " + taskTable()
                + " WHERE status = 'PENDING' AND available_at <= ? "
                + "ORDER BY available_at, created_at, task_id ";
        return dialect == JdbcDatabaseDialect.POSTGRESQL
                ? base + "LIMIT ? FOR UPDATE SKIP LOCKED"
                : base + "FOR UPDATE SKIP LOCKED";
    }

    private String claimSql() {
        return "/*inx:task-claim*/ UPDATE " + taskTable()
                + " SET status = 'RUNNING', attempts = attempts + 1, lease_owner = ?, "
                + "lease_version = lease_version + 1, lease_until = ?, updated_at = ? "
                + "WHERE task_id = ? AND status = 'PENDING'";
    }

    private String renewLeaseSql() {
        return "/*inx:task-renew*/ UPDATE " + taskTable()
                + " SET lease_until = ?, updated_at = ? WHERE task_id = ? AND status = 'RUNNING' "
                + "AND lease_owner = ? AND lease_version = ?";
    }

    private String saveCheckpointSql() {
        return "/*inx:task-checkpoint*/ UPDATE " + taskTable()
                + " SET checkpoint_sequence = COALESCE(checkpoint_sequence, 0) + 1, checkpoint_token = ?, "
                + "checkpoint_at = ?, lease_until = ?, updated_at = ? "
                + "WHERE task_id = ? AND status = 'RUNNING' AND lease_owner = ? AND lease_version = ? "
                + "AND cancellation_requested = 'N'";
    }

    private String selectCheckpointSql() {
        return "/*inx:task-checkpoint-read*/ SELECT checkpoint_sequence, checkpoint_token, checkpoint_at FROM "
                + taskTable() + " WHERE task_id = ?";
    }

    private String completeSql(String status, boolean clearFailure) {
        return "/*inx:task-complete*/ UPDATE " + taskTable()
                + " SET status = '" + status + "', "
                + (clearFailure ? "last_failure = NULL, " : "cancellation_requested = ?, ")
                + "lease_owner = NULL, lease_until = NULL, updated_at = ? "
                + "WHERE task_id = ? AND status = 'RUNNING' AND lease_owner = ? AND lease_version = ?";
    }

    private String failSql() {
        return "/*inx:task-fail*/ UPDATE " + taskTable()
                + " SET status = ?, available_at = ?, last_failure = ?, lease_owner = NULL, "
                + "lease_until = NULL, updated_at = ? WHERE task_id = ? AND status = 'RUNNING' "
                + "AND lease_owner = ? AND lease_version = ?";
    }

    private String selectCancellationStateSql() {
        return "/*inx:task-cancel-state*/ SELECT status, cancellation_requested FROM " + taskTable()
                + " WHERE task_id = ? FOR UPDATE";
    }

    private String requestCancellationSql() {
        return "/*inx:task-cancel*/ UPDATE " + taskTable()
                + " SET status = ?, cancellation_requested = 'Y', updated_at = ? WHERE task_id = ?";
    }

    private String selectLeaseStateSql(boolean forUpdate) {
        return "/*inx:task-lease-state*/ SELECT status, attempts, retry_safety, available_at, lease_owner, "
                + "lease_version, cancellation_requested FROM " + taskTable()
                + " WHERE task_id = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private String selectTasksSql(int count) {
        return "/*inx:task-read*/ SELECT task_id, task_type, idempotency_key, correlation_id, retry_safety, status, attempts, "
                + "available_at, lease_owner, lease_version, lease_until, checkpoint_sequence, checkpoint_token, "
                + "checkpoint_at, cancellation_requested, last_failure, created_at, updated_at FROM "
                + taskTable() + " WHERE task_id IN (" + placeholders(count) + ")";
    }

    private String selectParametersSql(int count) {
        return "/*inx:task-parameters*/ SELECT task_id, parameter_key, parameter_value FROM " + parameterTable()
                + " WHERE task_id IN (" + placeholders(count) + ") ORDER BY task_id, parameter_key";
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static boolean readBooleanMarker(ResultSet resultSet, String column) throws SQLException {
        String marker = resultSet.getString(column);
        if ("Y".equals(marker)) {
            return true;
        }
        if ("N".equals(marker)) {
            return false;
        }
        throw new SQLException("invalid boolean marker in " + column + ": " + marker);
    }

    private static TaskId requireTaskId(TaskId taskId) {
        return Objects.requireNonNull(taskId, "taskId");
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
        }
        return normalized;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Instant safeAdd(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (DateTimeException | ArithmeticException failure) {
            throw new IllegalArgumentException("time calculation overflow", failure);
        }
    }

    private static String sanitizeFailure(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        String rendered = message == null || message.isBlank() ? type : type + ": " + message.strip();
        return rendered.length() <= MAX_FAILURE_LENGTH ? rendered : rendered.substring(0, MAX_FAILURE_LENGTH);
    }

    private static void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(operation + " affected " + count + " rows instead of one");
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record SubmissionState(
            TaskId taskId,
            TaskType type,
            String idempotencyKey,
            Map<String, String> parameters,
            RetrySafety retrySafety,
            Instant requestedNotBefore) {
        boolean matches(TaskSubmission submission, RetrySafety safety) {
            return type.equals(submission.type())
                    && idempotencyKey.equals(submission.idempotencyKey())
                    && parameters.equals(submission.parameters())
                    && retrySafety == safety
                    && requestedNotBefore.equals(submission.notBefore());
        }
    }

    private record ExpiredLease(
            TaskId taskId,
            RetrySafety retrySafety,
            int attempts,
            Instant availableAt,
            long leaseVersion,
            boolean cancellationRequested) {}

    private record LeaseState(
            TaskStatus status,
            int attempts,
            RetrySafety retrySafety,
            Instant availableAt,
            String leaseOwner,
            long leaseVersion,
            boolean cancellationRequested) {}

    private record TaskRow(
            TaskId taskId,
            TaskType type,
            String idempotencyKey,
            DomainIdentifier correlationId,
            RetrySafety retrySafety,
            TaskStatus status,
            int attempts,
            Instant availableAt,
            String leaseOwner,
            long leaseVersion,
            Instant leaseUntil,
            TaskCheckpoint checkpoint,
            boolean cancellationRequested,
            String lastFailure,
            Instant createdAt,
            Instant updatedAt) {
        TaskRecord toRecord(Map<String, String> parameters) {
            return new TaskRecord(
                    taskId,
                    type,
                    idempotencyKey,
                    correlationId,
                    parameters,
                    retrySafety,
                    status,
                    attempts,
                    availableAt,
                    leaseOwner,
                    leaseVersion,
                    leaseUntil,
                    checkpoint,
                    cancellationRequested,
                    lastFailure,
                    createdAt,
                    updatedAt);
        }
    }
}
