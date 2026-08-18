package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncCheckpoint;
import io.infranexum.integrations.ConnectorSyncCheckpointKind;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncNotFoundException;
import io.infranexum.integrations.ConnectorSyncRepository;
import io.infranexum.integrations.ConnectorSyncRun;
import io.infranexum.integrations.ConnectorSyncRunStatus;
import io.infranexum.integrations.ConnectorSyncStateConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/** JDBC implementation of the fenced, append-only connector synchronization repository. */
public final class JdbcConnectorSyncRepository implements ConnectorSyncRepository {
    private static final int MAX_PAGE = 201;
    private static final String EMPTY_CURSOR_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcConnectorSyncRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public BeginResult begin(
            DomainIdentifier runId, ConnectorKey connectorKey, String provider,
            ConnectorSyncDirection direction, ConnectorRollbackStrategy rollbackStrategy,
            String idempotencyKey, String requestSha256, Set<String> fields, boolean propagateDeletions,
            int maxBatches, DomainIdentifier actorId, DomainIdentifier correlationId, Instant startedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(rollbackStrategy, "rollbackStrategy");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(startedAt, "startedAt");
        Set<String> stableFields = Set.copyOf(Objects.requireNonNullElse(fields, Set.of()));
        return tx("begin connector synchronization", connection -> {
            ensureState(connection, connectorKey, startedAt);
            State state = lockState(connection, connectorKey);
            Optional<ConnectorSyncRun> duplicate = findByIdempotency(connection, connectorKey, idempotencyKey, true);
            if (duplicate.isPresent()) {
                ConnectorSyncRun existing = duplicate.get();
                if (!existing.requestSha256().equals(requestSha256)) {
                    throw new ConnectorSyncStateConflictException("connector synchronization idempotency key was reused with different request semantics");
                }
                return new BeginResult(existing, state.cursor(), state.revision(), false);
            }
            if (state.activeRunId() != null) {
                throw new ConnectorSyncStateConflictException("connector already has an active synchronization run");
            }
            insertRun(connection, runId, connectorKey, provider, direction, rollbackStrategy, idempotencyKey,
                    requestSha256, stableFields, propagateDeletions, maxBatches, state.revision(), actorId, correlationId, startedAt);
            setActiveRun(connection, connectorKey, runId, startedAt);
            ConnectorSyncRun created = requireRun(connection, runId, false);
            return new BeginResult(created, state.cursor(), state.revision(), true);
        });
    }

    @Override
    public Activation activate(DomainIdentifier runId, Instant at) {
        Objects.requireNonNull(at, "at");
        return tx("activate connector synchronization", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() != ConnectorSyncRunStatus.PAUSED) {
                if (run.terminal()) throw new ConnectorSyncStateConflictException("terminal connector synchronization run cannot be resumed");
                throw new ConnectorSyncStateConflictException("connector synchronization run is not paused");
            }
            State state = lockState(connection, run.connectorKey());
            if (state.activeRunId() != null) throw new ConnectorSyncStateConflictException("connector already has an active synchronization run");
            if (state.revision() != run.lastCheckpointRevision()) throw new ConnectorSyncStateConflictException("connector advanced after the run checkpoint");
            updateRunStatus(connection, runId, ConnectorSyncRunStatus.RUNNING, null, null, null, at);
            setActiveRun(connection, run.connectorKey(), runId, at);
            return new Activation(requireRun(connection, runId, false), state.cursor(), state.revision());
        });
    }

    @Override
    public ConnectorSyncCheckpoint appendCheckpoint(
            DomainIdentifier checkpointId, DomainIdentifier runId, long expectedRevision,
            ConnectorSyncCheckpointKind kind, String cursor, String cursorSha256,
            long processedCount, long changedCount, long rejectedCount, Instant at) {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cursorSha256, "cursorSha256");
        Objects.requireNonNull(at, "at");
        return tx("append connector synchronization checkpoint", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() != ConnectorSyncRunStatus.RUNNING) throw new ConnectorSyncStateConflictException("progress checkpoint requires RUNNING state");
            State state = lockState(connection, run.connectorKey());
            requireActive(state, runId);
            if (state.revision() != expectedRevision) throw new ConnectorSyncStateConflictException("stale connector checkpoint revision");
            long revision = Math.addExact(expectedRevision, 1L);
            insertCheckpoint(connection, checkpointId, run.connectorKey(), runId, revision, kind, cursor, cursorSha256,
                    processedCount, changedCount, rejectedCount, at);
            updateStateProgress(connection, run.connectorKey(), revision, cursor, cursorSha256, runId, at);
            updateRunRevision(connection, runId, revision, at);
            return requireCheckpoint(connection, checkpointId);
        });
    }

    @Override public ConnectorSyncRun pause(DomainIdentifier runId, String failureCode, Instant at) { return finishActive(runId, ConnectorSyncRunStatus.PAUSED, failureCode, false, at); }
    @Override public ConnectorSyncRun succeed(DomainIdentifier runId, Instant at) { return finishActive(runId, ConnectorSyncRunStatus.SUCCEEDED, null, true, at); }
    @Override public ConnectorSyncRun fail(DomainIdentifier runId, String failureCode, Instant at) { return finishActive(runId, ConnectorSyncRunStatus.FAILED, failureCode, true, at); }

    @Override
    public CompensationStart beginCompensation(DomainIdentifier runId, Instant at) {
        Objects.requireNonNull(at, "at");
        return tx("begin connector synchronization compensation", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() == ConnectorSyncRunStatus.COMPENSATED) throw new ConnectorSyncStateConflictException("connector synchronization run is already compensated");
            if (run.status() == ConnectorSyncRunStatus.COMPENSATING) throw new ConnectorSyncStateConflictException("connector synchronization run is already compensating");
            if (run.status() == ConnectorSyncRunStatus.COMPENSATION_FAILED) throw new ConnectorSyncStateConflictException("failed compensation requires explicit operator recovery");
            State state = lockState(connection, run.connectorKey());
            if (state.activeRunId() != null && !state.activeRunId().equals(runId)) {
                throw new ConnectorSyncStateConflictException("another connector synchronization run owns the state fence");
            }
            if (state.revision() != run.lastCheckpointRevision()) {
                throw new ConnectorSyncStateConflictException("connector has advanced beyond the run; compensation would overwrite newer state");
            }
            String initialCursor = cursorAtRevision(connection, run.connectorKey(), run.initialRevision());
            updateRunStatus(connection, runId, ConnectorSyncRunStatus.COMPENSATING, run.failureCode(), null, null, at);
            setActiveRun(connection, run.connectorKey(), runId, at);
            return new CompensationStart(requireRun(connection, runId, false), initialCursor, state.cursor(), state.revision());
        });
    }

    @Override
    public ConnectorSyncRun finishCompensation(
            DomainIdentifier runId, long expectedRevision, DomainIdentifier checkpointId,
            String restoredCursor, String restoredCursorSha256, Instant at) {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(restoredCursorSha256, "restoredCursorSha256");
        Objects.requireNonNull(at, "at");
        return tx("finish connector synchronization compensation", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() != ConnectorSyncRunStatus.COMPENSATING) throw new ConnectorSyncStateConflictException("connector run is not compensating");
            State state = lockState(connection, run.connectorKey());
            requireActive(state, runId);
            if (state.revision() != expectedRevision) throw new ConnectorSyncStateConflictException("connector advanced during compensation");
            long revision = Math.addExact(expectedRevision, 1L);
            insertCheckpoint(connection, checkpointId, run.connectorKey(), runId, revision, ConnectorSyncCheckpointKind.COMPENSATION,
                    restoredCursor, restoredCursorSha256, 0, 0, 0, at);
            updateStateProgress(connection, run.connectorKey(), revision, restoredCursor, restoredCursorSha256, null, at);
            updateRunStatus(connection, runId, ConnectorSyncRunStatus.COMPENSATED, null, at, revision, at);
            updateRunRevision(connection, runId, revision, at);
            return requireRun(connection, runId, false);
        });
    }

    @Override
    public ConnectorSyncRun compensationFailed(DomainIdentifier runId, String failureCode, Instant at) {
        Objects.requireNonNull(at, "at");
        return tx("fail connector synchronization compensation", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() != ConnectorSyncRunStatus.COMPENSATING) throw new ConnectorSyncStateConflictException("connector run is not compensating");
            State state = lockState(connection, run.connectorKey());
            requireActive(state, runId);
            clearActiveRun(connection, run.connectorKey(), at);
            updateRunStatus(connection, runId, ConnectorSyncRunStatus.COMPENSATION_FAILED, failureCode, at, null, at);
            return requireRun(connection, runId, false);
        });
    }

    @Override
    public Optional<ConnectorSyncRun> findRun(DomainIdentifier runId) {
        return tx("find connector synchronization run", connection -> findRun(connection, runId, false));
    }

    @Override
    public List<ConnectorSyncRun> listRuns(ConnectorKey connectorKey, int offset, int limit) {
        boundedPage(offset, limit);
        return tx("list connector synchronization runs", connection -> {
            String predicate = connectorKey == null ? "" : " WHERE connector_key=?";
            String suffix = dialect == JdbcDatabaseDialect.POSTGRESQL ? " ORDER BY started_at DESC,run_id DESC LIMIT ? OFFSET ?" : " ORDER BY started_at DESC,run_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + runTable() + predicate + suffix)) {
                int i = 1;
                if (connectorKey != null) statement.setString(i++, connectorKey.value());
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) { statement.setInt(i++, limit); statement.setInt(i, offset); }
                else { statement.setInt(i++, offset); statement.setInt(i, limit); }
                try (ResultSet resultSet = statement.executeQuery()) { return readRuns(resultSet); }
            }
        });
    }

    @Override
    public List<ConnectorSyncCheckpoint> listCheckpoints(ConnectorKey connectorKey, int offset, int limit) {
        Objects.requireNonNull(connectorKey, "connectorKey");
        boundedPage(offset, limit);
        return tx("list connector synchronization checkpoints", connection -> {
            String suffix = dialect == JdbcDatabaseDialect.POSTGRESQL ? " ORDER BY revision DESC LIMIT ? OFFSET ?" : " ORDER BY revision DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + checkpointTable() + " WHERE connector_key=?" + suffix)) {
                statement.setString(1, connectorKey.value());
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) { statement.setInt(2, limit); statement.setInt(3, offset); }
                else { statement.setInt(2, offset); statement.setInt(3, limit); }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<ConnectorSyncCheckpoint> out = new ArrayList<>();
                    while (resultSet.next()) out.add(readCheckpoint(resultSet));
                    return List.copyOf(out);
                }
            }
        });
    }

    private ConnectorSyncRun finishActive(DomainIdentifier runId, ConnectorSyncRunStatus target, String failureCode, boolean completed, Instant at) {
        Objects.requireNonNull(at, "at");
        return tx("finish connector synchronization run", connection -> {
            ConnectorSyncRun run = requireRun(connection, runId, true);
            if (run.status() != ConnectorSyncRunStatus.RUNNING) throw new ConnectorSyncStateConflictException("connector synchronization run is not RUNNING");
            State state = lockState(connection, run.connectorKey());
            requireActive(state, runId);
            clearActiveRun(connection, run.connectorKey(), at);
            updateRunStatus(connection, runId, target, failureCode, completed ? at : null, null, at);
            return requireRun(connection, runId, false);
        });
    }

    private void ensureState(Connection connection, ConnectorKey key, Instant at) throws SQLException {
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO infranexum_integrations.connector_sync_state(connector_key,current_revision,cursor_value,cursor_sha256,active_run_id,updated_at) VALUES(?,0,NULL,?,NULL,?) ON CONFLICT(connector_key) DO NOTHING")) {
                statement.setString(1, key.value()); statement.setString(2, EMPTY_CURSOR_SHA256); JdbcTemporal.bindInstant(statement, 3, at); statement.executeUpdate();
            }
            return;
        }
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO INFRANEXUM_SYNC_STATE(CONNECTOR_KEY,CURRENT_REVISION,CURSOR_VALUE,CURSOR_SHA256,ACTIVE_RUN_ID,UPDATED_AT) VALUES(?,0,NULL,?,NULL,?)")) {
            statement.setString(1, key.value()); statement.setString(2, EMPTY_CURSOR_SHA256); JdbcTemporal.bindInstant(statement, 3, at); statement.executeUpdate(); connection.releaseSavepoint(savepoint);
        } catch (SQLException failure) {
            connection.rollback(savepoint); connection.releaseSavepoint(savepoint);
            if (!dialect.isUniqueViolation(failure)) throw failure;
        }
    }

    private State lockState(Connection connection, ConnectorKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_revision,cursor_value,cursor_sha256,active_run_id FROM " + stateTable() + " WHERE connector_key=? FOR UPDATE")) {
            statement.setString(1, key.value());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new ConnectorSyncNotFoundException("connector synchronization state not found: " + key.value());
                return new State(rs.getLong("current_revision"), rs.getString("cursor_value"), rs.getString("cursor_sha256"), nullableId(rs, "active_run_id"));
            }
        }
    }

    private void insertRun(Connection c, DomainIdentifier runId, ConnectorKey key, String provider, ConnectorSyncDirection direction,
            ConnectorRollbackStrategy rollback, String idem, String hash, Set<String> fields, boolean deletions, int maxBatches,
            long revision, DomainIdentifier actor, DomainIdentifier correlation, Instant at) throws SQLException {
        String sql = "INSERT INTO " + runTable() + "(run_id,connector_key,provider,direction,rollback_strategy,status,idempotency_key,request_sha256,requested_fields,propagate_deletions,max_batches,initial_revision,last_checkpoint_revision,failure_code,actor_id,correlation_id,started_at,updated_at,completed_at,compensation_checkpoint_revision) VALUES(?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?,NULL,?,?,?,?,NULL,NULL)";
        try (PreparedStatement st = c.prepareStatement(sql)) {
            int i=1; dialect.bindIdentifier(st,i++,runId); st.setString(i++,key.value()); st.setString(i++,provider); st.setString(i++,direction.name()); st.setString(i++,rollback.name()); st.setString(i++,idem); st.setString(i++,hash); st.setString(i++,encodeFields(fields));
            if (dialect == JdbcDatabaseDialect.POSTGRESQL) st.setBoolean(i++,deletions); else st.setInt(i++,deletions?1:0);
            st.setInt(i++,maxBatches); st.setLong(i++,revision); st.setLong(i++,revision); dialect.bindIdentifier(st,i++,actor); dialect.bindIdentifier(st,i++,correlation); JdbcTemporal.bindInstant(st,i++,at); JdbcTemporal.bindInstant(st,i,at);
            requireSingle(st.executeUpdate(), "insert connector sync run");
        }
    }

    private void insertCheckpoint(Connection c, DomainIdentifier checkpointId, ConnectorKey key, DomainIdentifier runId, long revision,
            ConnectorSyncCheckpointKind kind, String cursor, String hash, long processed, long changed, long rejected, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("INSERT INTO " + checkpointTable() + "(checkpoint_id,connector_key,run_id,revision,kind,cursor_value,cursor_sha256,processed_count,changed_count,rejected_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            int i=1; dialect.bindIdentifier(st,i++,checkpointId); st.setString(i++,key.value()); dialect.bindIdentifier(st,i++,runId); st.setLong(i++,revision); st.setString(i++,kind.name()); st.setString(i++,cursor); st.setString(i++,hash); st.setLong(i++,processed); st.setLong(i++,changed); st.setLong(i++,rejected); JdbcTemporal.bindInstant(st,i,at); requireSingle(st.executeUpdate(),"insert connector sync checkpoint");
        }
    }

    private void setActiveRun(Connection c, ConnectorKey key, DomainIdentifier runId, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + stateTable() + " SET active_run_id=?,updated_at=? WHERE connector_key=?")) {
            dialect.bindIdentifier(st,1,runId); JdbcTemporal.bindInstant(st,2,at); st.setString(3,key.value()); requireSingle(st.executeUpdate(),"set active sync run");
        }
    }

    private void clearActiveRun(Connection c, ConnectorKey key, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + stateTable() + " SET active_run_id=NULL,updated_at=? WHERE connector_key=?")) {
            JdbcTemporal.bindInstant(st,1,at); st.setString(2,key.value()); requireSingle(st.executeUpdate(),"clear active sync run");
        }
    }

    private void updateStateProgress(Connection c, ConnectorKey key, long revision, String cursor, String hash, DomainIdentifier activeRun, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + stateTable() + " SET current_revision=?,cursor_value=?,cursor_sha256=?,active_run_id=?,updated_at=? WHERE connector_key=?")) {
            st.setLong(1,revision); st.setString(2,cursor); st.setString(3,hash); dialect.bindNullableIdentifier(st,4,activeRun); JdbcTemporal.bindInstant(st,5,at); st.setString(6,key.value()); requireSingle(st.executeUpdate(),"update connector sync state");
        }
    }

    private void updateRunRevision(Connection c, DomainIdentifier runId, long revision, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + runTable() + " SET last_checkpoint_revision=?,updated_at=? WHERE run_id=?")) {
            st.setLong(1,revision); JdbcTemporal.bindInstant(st,2,at); dialect.bindIdentifier(st,3,runId); requireSingle(st.executeUpdate(),"update sync run revision");
        }
    }

    private void updateRunStatus(Connection c, DomainIdentifier runId, ConnectorSyncRunStatus status, String failureCode, Instant completedAt, Long compensationRevision, Instant at) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + runTable() + " SET status=?,failure_code=?,completed_at=?,compensation_checkpoint_revision=?,updated_at=? WHERE run_id=?")) {
            st.setString(1,status.name()); st.setString(2,failureCode); JdbcTemporal.bindInstant(st,3,completedAt); if(compensationRevision==null)st.setNull(4,java.sql.Types.BIGINT);else st.setLong(4,compensationRevision); JdbcTemporal.bindInstant(st,5,at); dialect.bindIdentifier(st,6,runId); requireSingle(st.executeUpdate(),"update sync run status");
        }
    }

    private Optional<ConnectorSyncRun> findByIdempotency(Connection c, ConnectorKey key, String idem, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement st = c.prepareStatement("SELECT * FROM " + runTable() + " WHERE connector_key=? AND idempotency_key=?" + suffix)) {
            st.setString(1,key.value()); st.setString(2,idem); try(ResultSet rs=st.executeQuery()){return rs.next()?Optional.of(readRun(rs)):Optional.empty();}
        }
    }

    private Optional<ConnectorSyncRun> findRun(Connection c, DomainIdentifier runId, boolean lock) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT * FROM " + runTable() + " WHERE run_id=?" + (lock?" FOR UPDATE":""))) {
            dialect.bindIdentifier(st,1,runId); try(ResultSet rs=st.executeQuery()){return rs.next()?Optional.of(readRun(rs)):Optional.empty();}
        }
    }

    private ConnectorSyncRun requireRun(Connection c, DomainIdentifier runId, boolean lock) throws SQLException {
        return findRun(c,runId,lock).orElseThrow(() -> new ConnectorSyncNotFoundException("connector synchronization run not found: " + runId));
    }

    private ConnectorSyncCheckpoint requireCheckpoint(Connection c, DomainIdentifier checkpointId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT * FROM " + checkpointTable() + " WHERE checkpoint_id=?")) {
            dialect.bindIdentifier(st,1,checkpointId); try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new ConnectorSyncNotFoundException("connector synchronization checkpoint not found: "+checkpointId);return readCheckpoint(rs);}
        }
    }

    private String cursorAtRevision(Connection c, ConnectorKey key, long revision) throws SQLException {
        if (revision == 0) return null;
        try (PreparedStatement st = c.prepareStatement("SELECT cursor_value FROM " + checkpointTable() + " WHERE connector_key=? AND revision=?")) {
            st.setString(1,key.value()); st.setLong(2,revision); try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new ConnectorSyncStateConflictException("initial connector checkpoint no longer exists");return rs.getString(1);}
        }
    }

    private List<ConnectorSyncRun> readRuns(ResultSet rs) throws SQLException { List<ConnectorSyncRun> out=new ArrayList<>(); while(rs.next())out.add(readRun(rs)); return List.copyOf(out); }

    private ConnectorSyncRun readRun(ResultSet rs) throws SQLException {
        Object deletions = rs.getObject("propagate_deletions");
        boolean propagate = deletions instanceof Boolean value ? value : rs.getInt("propagate_deletions") != 0;
        long compensation = rs.getLong("compensation_checkpoint_revision");
        Long compensationRevision = rs.wasNull() ? null : compensation;
        return new ConnectorSyncRun(
                dialect.readIdentifier(rs,"run_id"), new ConnectorKey(rs.getString("connector_key")), rs.getString("provider"), ConnectorSyncDirection.valueOf(rs.getString("direction")),
                ConnectorRollbackStrategy.valueOf(rs.getString("rollback_strategy")), ConnectorSyncRunStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"), rs.getString("request_sha256"),
                decodeFields(rs.getString("requested_fields")), propagate, rs.getInt("max_batches"), rs.getLong("initial_revision"), rs.getLong("last_checkpoint_revision"), rs.getString("failure_code"),
                dialect.readIdentifier(rs,"actor_id"), dialect.readIdentifier(rs,"correlation_id"), JdbcTemporal.readRequired(rs,"started_at"), JdbcTemporal.readRequired(rs,"updated_at"), JdbcTemporal.readNullable(rs,"completed_at"), compensationRevision);
    }

    private ConnectorSyncCheckpoint readCheckpoint(ResultSet rs) throws SQLException {
        return new ConnectorSyncCheckpoint(dialect.readIdentifier(rs,"checkpoint_id"), new ConnectorKey(rs.getString("connector_key")), dialect.readIdentifier(rs,"run_id"), rs.getLong("revision"),
                ConnectorSyncCheckpointKind.valueOf(rs.getString("kind")), rs.getString("cursor_value"), rs.getString("cursor_sha256"), rs.getLong("processed_count"), rs.getLong("changed_count"), rs.getLong("rejected_count"), JdbcTemporal.readRequired(rs,"created_at"));
    }

    private static void requireActive(State state, DomainIdentifier runId) {
        if (state.activeRunId() == null || !state.activeRunId().equals(runId)) throw new ConnectorSyncStateConflictException("connector synchronization run does not own the active state fence");
    }

    private DomainIdentifier nullableId(ResultSet rs, String column) throws SQLException { Object value=rs.getObject(column); return value==null?null:dialect.readIdentifier(rs,column); }
    private String stateTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.connector_sync_state":"INFRANEXUM_SYNC_STATE";}
    private String runTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.connector_sync_run":"INFRANEXUM_SYNC_RUN";}
    private String checkpointTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.connector_sync_checkpoint":"INFRANEXUM_SYNC_CHECKPOINT";}
    private static String encodeFields(Set<String> fields){return String.join(",",fields.stream().sorted().toList());}
    private static Set<String> decodeFields(String encoded){if(encoded==null||encoded.isEmpty())return Set.of();LinkedHashSet<String> out=new LinkedHashSet<>();for(String field:encoded.split(","))out.add(field);return Set.copyOf(out);}
    private static void boundedPage(int offset,int limit){if(offset<0||offset>1_000_000)throw new IllegalArgumentException("offset out of range");if(limit<1||limit>MAX_PAGE)throw new IllegalArgumentException("limit must be between 1 and 201");}
    private static void requireSingle(int count,String operation){if(count!=1)throw new IllegalStateException(operation+" affected "+count+" rows");}

    private <T>T tx(String operation, SqlWork<T> work){try(Connection c=dataSource.getConnection()){boolean old=c.getAutoCommit();if(old)c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}if(failure instanceof SQLException sql)throw new JdbcPersistenceException(operation,sql);throw failure;}finally{if(old)try{c.setAutoCommit(true);}catch(SQLException ignored){}}}catch(SQLException failure){throw new JdbcPersistenceException(operation,failure);}}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    private record State(long revision,String cursor,String cursorSha256,DomainIdentifier activeRunId){}
}
