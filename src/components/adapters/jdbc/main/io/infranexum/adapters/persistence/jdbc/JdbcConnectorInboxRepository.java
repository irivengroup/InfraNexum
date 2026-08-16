package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.ConnectorDelivery;
import io.infranexum.integrations.ConnectorDeliveryStatus;
import io.infranexum.integrations.ConnectorInboxRepository;
import io.infranexum.integrations.ConnectorDeliveryNotFoundException;
import io.infranexum.integrations.ConnectorDeliveryStateConflictException;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRuntimeState;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.WebhookAdmission;
import io.infranexum.integrations.WebhookAdmissionOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** JDBC connector inbox/DLQ implementation with PostgreSQL/Oracle parity and lease fencing. */
public final class JdbcConnectorInboxRepository implements ConnectorInboxRepository {
    private static final int MAX_BATCH = 1_000;
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcConnectorInboxRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public WebhookAdmissionOutcome admit(WebhookAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        return tx("admit connector webhook", connection -> {
            if (insertAdmission(connection, admission)) {
                return new WebhookAdmissionOutcome(readById(connection, admission.deliveryId()), false);
            }
            ConnectorDelivery existing = readByExternalId(connection, admission.connectorKey(), admission.externalDeliveryId());
            if (!existing.payloadSha256().equals(admission.payloadSha256())) {
                throw new DuplicateDeliveryConflictException("provider delivery identifier was reused with different content");
            }
            return new WebhookAdmissionOutcome(existing, true);
        });
    }

    @Override
    public List<ConnectorDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
        String worker = required(workerId, "workerId", 160);
        boundedLimit(limit);
        Objects.requireNonNull(now, "now");
        positive(leaseDuration, "leaseDuration");
        return tx("claim connector inbox", connection -> dialect == JdbcDatabaseDialect.POSTGRESQL
                ? claimPostgresql(connection, worker, limit, now, now.plus(leaseDuration))
                : claimOracle(connection, worker, limit, now, now.plus(leaseDuration)));
    }

    @Override
    public void markProcessed(DomainIdentifier deliveryId, String workerId, Instant processedAt) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        String worker = required(workerId, "workerId", 160);
        Objects.requireNonNull(processedAt, "processedAt");
        tx("complete connector inbox", connection -> {
            ConnectorDelivery current = requireLease(connection, deliveryId, worker);
            try (PreparedStatement statement = connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.connector_inbox SET status='PROCESSED', processed_at=?, lease_owner=NULL, lease_until=NULL, last_failure=NULL, updated_at=? WHERE delivery_id=? AND status='IN_FLIGHT' AND lease_owner=?",
                    "UPDATE INFRANEXUM_INTEGRATION_INBOX SET STATUS='PROCESSED', PROCESSED_AT=?, LEASE_OWNER=NULL, LEASE_UNTIL=NULL, LAST_FAILURE=NULL, UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='IN_FLIGHT' AND LEASE_OWNER=?"))) {
                JdbcTemporal.bindInstant(statement, 1, processedAt);
                JdbcTemporal.bindInstant(statement, 2, processedAt);
                dialect.bindIdentifier(statement, 3, deliveryId);
                statement.setString(4, worker);
                requireSingle(statement.executeUpdate(), "complete connector delivery");
            }
            upsertSuccess(connection, current.connectorKey(), processedAt);
            return null;
        });
    }

    @Override
    public ConnectorDeliveryStatus markFailed(
            DomainIdentifier deliveryId,
            String workerId,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure,
            int suspendAfterDeadLetters,
            Duration suspensionDuration) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        String worker = required(workerId, "workerId", 160);
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(failure, "failure");
        if (suspendAfterDeadLetters < 1 || suspendAfterDeadLetters > 100) throw new IllegalArgumentException("suspendAfterDeadLetters must be between 1 and 100");
        positive(suspensionDuration, "suspensionDuration");
        return tx("fail connector inbox", connection -> {
            ConnectorDelivery current = requireLease(connection, deliveryId, worker);
            boolean dead = current.attempts() >= retryPolicy.maximumAttempts();
            ConnectorDeliveryStatus next = dead ? ConnectorDeliveryStatus.DEAD_LETTER : ConnectorDeliveryStatus.PENDING;
            Instant availableAt = dead ? failedAt : failedAt.plus(retryPolicy.delayAfterFailure(current.attempts()));
            try (PreparedStatement statement = connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.connector_inbox SET status=?, available_at=?, lease_owner=NULL, lease_until=NULL, last_failure=?, updated_at=? WHERE delivery_id=? AND status='IN_FLIGHT' AND lease_owner=?",
                    "UPDATE INFRANEXUM_INTEGRATION_INBOX SET STATUS=?, AVAILABLE_AT=?, LEASE_OWNER=NULL, LEASE_UNTIL=NULL, LAST_FAILURE=?, UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='IN_FLIGHT' AND LEASE_OWNER=?"))) {
                statement.setString(1, next.name());
                JdbcTemporal.bindInstant(statement, 2, availableAt);
                statement.setString(3, failure.getClass().getName());
                JdbcTemporal.bindInstant(statement, 4, failedAt);
                dialect.bindIdentifier(statement, 5, deliveryId);
                statement.setString(6, worker);
                requireSingle(statement.executeUpdate(), "fail connector delivery");
            }
            if (dead) upsertDeadLetter(connection, current.connectorKey(), failedAt, suspendAfterDeadLetters, suspensionDuration);
            return next;
        });
    }

    @Override
    public List<ConnectorDelivery> listDeadLetters(ConnectorKey connectorKey, int offset, int limit) {
        if (offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("offset must be between 0 and 1000000");
        boundedLimit(limit);
        return tx("list connector dead letters", connection -> {
            String connectorPredicate = connectorKey == null ? "" : " AND connector_key=?";
            String query = dialect == JdbcDatabaseDialect.POSTGRESQL
                    ? "SELECT * FROM infranexum_integrations.connector_inbox WHERE status='DEAD_LETTER'" + connectorPredicate + " ORDER BY received_at, delivery_id LIMIT ? OFFSET ?"
                    : "SELECT * FROM INFRANEXUM_INTEGRATION_INBOX WHERE STATUS='DEAD_LETTER'" + (connectorKey == null ? "" : " AND CONNECTOR_KEY=?") + " ORDER BY RECEIVED_AT, DELIVERY_ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                int index = 1;
                if (connectorKey != null) statement.setString(index++, connectorKey.value());
                if (dialect == JdbcDatabaseDialect.POSTGRESQL) { statement.setInt(index++, limit); statement.setInt(index, offset); }
                else { statement.setInt(index++, offset); statement.setInt(index, limit); }
                try (ResultSet resultSet = statement.executeQuery()) { return readAll(resultSet); }
            }
        });
    }

    @Override
    public ConnectorDelivery replay(DomainIdentifier deliveryId, Instant replayedAt) {
        Objects.requireNonNull(deliveryId, "deliveryId"); Objects.requireNonNull(replayedAt, "replayedAt");
        return tx("replay connector dead letter", connection -> {
            ConnectorDelivery current = readByIdForUpdate(connection, deliveryId);
            if (current.status() != ConnectorDeliveryStatus.DEAD_LETTER) throw new ConnectorDeliveryStateConflictException("only DEAD_LETTER deliveries may be replayed");
            try (PreparedStatement statement = connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.connector_inbox SET status='PENDING', attempts=0, available_at=?, lease_owner=NULL, lease_until=NULL, processed_at=NULL, last_failure=NULL, replay_count=replay_count+1, last_replayed_at=?, updated_at=? WHERE delivery_id=? AND status='DEAD_LETTER'",
                    "UPDATE INFRANEXUM_INTEGRATION_INBOX SET STATUS='PENDING', ATTEMPTS=0, AVAILABLE_AT=?, LEASE_OWNER=NULL, LEASE_UNTIL=NULL, PROCESSED_AT=NULL, LAST_FAILURE=NULL, REPLAY_COUNT=REPLAY_COUNT+1, LAST_REPLAYED_AT=?, UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='DEAD_LETTER'"))) {
                JdbcTemporal.bindInstant(statement, 1, replayedAt); JdbcTemporal.bindInstant(statement, 2, replayedAt); JdbcTemporal.bindInstant(statement, 3, replayedAt); dialect.bindIdentifier(statement, 4, deliveryId);
                requireSingle(statement.executeUpdate(), "replay connector delivery");
            }
            return readById(connection, deliveryId);
        });
    }

    @Override
    public ConnectorRuntimeState runtimeState(ConnectorKey connectorKey) {
        Objects.requireNonNull(connectorKey, "connectorKey");
        return tx("read connector runtime state", connection -> readRuntimeState(connection, connectorKey));
    }

    @Override
    public ConnectorRuntimeState resume(ConnectorKey connectorKey, Instant resumedAt) {
        Objects.requireNonNull(connectorKey, "connectorKey"); Objects.requireNonNull(resumedAt, "resumedAt");
        return tx("resume connector", connection -> { upsertResume(connection, connectorKey, resumedAt); return readRuntimeState(connection, connectorKey); });
    }

    @Override
    public long backlogSize(ConnectorKey connectorKey, Instant now) {
        Objects.requireNonNull(connectorKey, "connectorKey"); Objects.requireNonNull(now, "now");
        return count("connector backlog", connectorKey, "status IN ('PENDING','IN_FLIGHT')");
    }

    @Override
    public long deadLetterCount(ConnectorKey connectorKey) {
        Objects.requireNonNull(connectorKey, "connectorKey");
        return count("connector dead letters", connectorKey, "status='DEAD_LETTER'");
    }

    private long count(String operation, ConnectorKey key, String predicate) {
        return tx(operation, connection -> {
            String query = "SELECT COUNT(*) FROM " + table() + " WHERE connector_key=? AND " + predicate;
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, key.value()); try (ResultSet rs = statement.executeQuery()) { if (!rs.next()) throw new SQLException("count query returned no row"); return rs.getLong(1); }
            }
        });
    }

    private boolean insertAdmission(Connection connection, WebhookAdmission admission) throws SQLException {
        String query = sql(
                "INSERT INTO infranexum_integrations.connector_inbox(delivery_id,connector_key,external_delivery_id,payload_raw,payload_json,payload_sha256,status,attempts,received_at,available_at,lease_owner,lease_until,processed_at,last_failure,replay_count,last_replayed_at,created_at,updated_at) VALUES(?,?,?,?,CAST(? AS JSONB),?,'PENDING',0,?,?,NULL,NULL,NULL,NULL,0,NULL,?,?) ON CONFLICT(connector_key,external_delivery_id) DO NOTHING",
                "INSERT INTO INFRANEXUM_INTEGRATION_INBOX(DELIVERY_ID,CONNECTOR_KEY,EXTERNAL_DELIVERY_ID,PAYLOAD_RAW,PAYLOAD_JSON,PAYLOAD_SHA256,STATUS,ATTEMPTS,RECEIVED_AT,AVAILABLE_AT,LEASE_OWNER,LEASE_UNTIL,PROCESSED_AT,LAST_FAILURE,REPLAY_COUNT,LAST_REPLAYED_AT,CREATED_AT,UPDATED_AT) VALUES(?,?,?,?,?,?,'PENDING',0,?,?,NULL,NULL,NULL,NULL,0,NULL,?,?)");
        Savepoint savepoint = dialect == JdbcDatabaseDialect.ORACLE ? connection.setSavepoint() : null;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            dialect.bindIdentifier(statement, 1, admission.deliveryId()); statement.setString(2, admission.connectorKey().value()); statement.setString(3, admission.externalDeliveryId()); dialect.bindText(statement, 4, admission.payload()); dialect.bindJson(statement, 5, admission.payload()); statement.setString(6, admission.payloadSha256()); JdbcTemporal.bindInstant(statement, 7, admission.receivedAt()); JdbcTemporal.bindInstant(statement, 8, admission.receivedAt()); JdbcTemporal.bindInstant(statement, 9, admission.receivedAt()); JdbcTemporal.bindInstant(statement, 10, admission.receivedAt());
            boolean inserted = statement.executeUpdate() == 1;
            if (savepoint != null) connection.releaseSavepoint(savepoint);
            return inserted;
        } catch (SQLException failure) {
            if (savepoint != null && dialect.isUniqueViolation(failure)) { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); return false; }
            throw failure;
        }
    }

    private List<ConnectorDelivery> claimPostgresql(Connection connection,String worker,int limit,Instant now,Instant leaseUntil)throws SQLException{
        String query="""
                WITH candidates AS (
                  SELECT i.delivery_id FROM infranexum_integrations.connector_inbox i
                  LEFT JOIN infranexum_integrations.connector_runtime_state s ON s.connector_key=i.connector_key
                  WHERE ((i.status='PENDING' AND i.available_at<=?) OR (i.status='IN_FLIGHT' AND i.lease_until<=?))
                    AND (s.suspended_until IS NULL OR s.suspended_until<=?)
                  ORDER BY i.available_at,i.received_at,i.delivery_id LIMIT ? FOR UPDATE OF i SKIP LOCKED
                )
                UPDATE infranexum_integrations.connector_inbox i SET status='IN_FLIGHT',attempts=i.attempts+1,lease_owner=?,lease_until=?,updated_at=?
                FROM candidates WHERE i.delivery_id=candidates.delivery_id RETURNING i.*
                """;
        try(PreparedStatement st=connection.prepareStatement(query)){JdbcTemporal.bindInstant(st,1,now);JdbcTemporal.bindInstant(st,2,now);JdbcTemporal.bindInstant(st,3,now);st.setInt(4,limit);st.setString(5,worker);JdbcTemporal.bindInstant(st,6,leaseUntil);JdbcTemporal.bindInstant(st,7,now);try(ResultSet rs=st.executeQuery()){return readAll(rs);}}
    }

    private List<ConnectorDelivery> claimOracle(Connection connection,String worker,int limit,Instant now,Instant leaseUntil)throws SQLException{
        String query="SELECT i.* FROM INFRANEXUM_INTEGRATION_INBOX i LEFT JOIN INFRANEXUM_INTEGRATION_STATE s ON s.CONNECTOR_KEY=i.CONNECTOR_KEY WHERE ((i.STATUS='PENDING' AND i.AVAILABLE_AT<=?) OR (i.STATUS='IN_FLIGHT' AND i.LEASE_UNTIL<=?)) AND (s.SUSPENDED_UNTIL IS NULL OR s.SUSPENDED_UNTIL<=?) ORDER BY i.AVAILABLE_AT,i.RECEIVED_AT,i.DELIVERY_ID FOR UPDATE OF i.DELIVERY_ID SKIP LOCKED";
        List<ConnectorDelivery> selected;try(PreparedStatement st=connection.prepareStatement(query)){JdbcTemporal.bindInstant(st,1,now);JdbcTemporal.bindInstant(st,2,now);JdbcTemporal.bindInstant(st,3,now);st.setMaxRows(limit);try(ResultSet rs=st.executeQuery()){selected=readAll(rs);}}
        List<ConnectorDelivery> claimed=new ArrayList<>();String update="UPDATE INFRANEXUM_INTEGRATION_INBOX SET STATUS='IN_FLIGHT',ATTEMPTS=ATTEMPTS+1,LEASE_OWNER=?,LEASE_UNTIL=?,UPDATED_AT=? WHERE DELIVERY_ID=?";
        try(PreparedStatement st=connection.prepareStatement(update)){for(ConnectorDelivery item:selected){st.setString(1,worker);JdbcTemporal.bindInstant(st,2,leaseUntil);JdbcTemporal.bindInstant(st,3,now);dialect.bindIdentifier(st,4,item.deliveryId());requireSingle(st.executeUpdate(),"claim Oracle connector delivery");claimed.add(new ConnectorDelivery(item.deliveryId(),item.connectorKey(),item.externalDeliveryId(),item.payload(),item.payloadSha256(),ConnectorDeliveryStatus.IN_FLIGHT,item.attempts()+1,item.receivedAt(),item.availableAt(),worker,leaseUntil,null,item.lastFailure(),item.replayCount(),item.lastReplayedAt()));}}
        return List.copyOf(claimed);
    }

    private ConnectorDelivery requireLease(Connection connection,DomainIdentifier id,String worker)throws SQLException{ConnectorDelivery current=readByIdForUpdate(connection,id);if(current.status()!=ConnectorDeliveryStatus.IN_FLIGHT||!worker.equals(current.leaseOwner()))throw new IllegalStateException("connector delivery is not leased by worker "+worker);return current;}
    private ConnectorDelivery readById(Connection c,DomainIdentifier id)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE delivery_id=?",st->dialect.bindIdentifier(st,1,id),"unknown connector delivery: "+id);}
    private ConnectorDelivery readByIdForUpdate(Connection c,DomainIdentifier id)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE delivery_id=? FOR UPDATE",st->dialect.bindIdentifier(st,1,id),"unknown connector delivery: "+id);}
    private ConnectorDelivery readByExternalId(Connection c,ConnectorKey key,String ext)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE connector_key=? AND external_delivery_id=?",st->{st.setString(1,key.value());st.setString(2,ext);},"connector delivery disappeared after uniqueness conflict");}
    private ConnectorDelivery readOne(Connection c,String sql,StatementBinder binder,String missing)throws SQLException{try(PreparedStatement st=c.prepareStatement(sql)){binder.bind(st);try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new ConnectorDeliveryNotFoundException(missing);return read(rs);}}}
    private List<ConnectorDelivery> readAll(ResultSet rs)throws SQLException{List<ConnectorDelivery> out=new ArrayList<>();while(rs.next())out.add(read(rs));return List.copyOf(out);}
    private ConnectorDelivery read(ResultSet rs)throws SQLException{return new ConnectorDelivery(dialect.readIdentifier(rs,"delivery_id"),new ConnectorKey(rs.getString("connector_key")),rs.getString("external_delivery_id"),rs.getString("payload_raw"),rs.getString("payload_sha256"),ConnectorDeliveryStatus.valueOf(rs.getString("status")),rs.getInt("attempts"),JdbcTemporal.readRequired(rs,"received_at"),JdbcTemporal.readRequired(rs,"available_at"),rs.getString("lease_owner"),JdbcTemporal.readNullable(rs,"lease_until"),JdbcTemporal.readNullable(rs,"processed_at"),rs.getString("last_failure"),rs.getInt("replay_count"),JdbcTemporal.readNullable(rs,"last_replayed_at"));}

    private ConnectorRuntimeState readRuntimeState(Connection c,ConnectorKey key)throws SQLException{String q="SELECT consecutive_dead_letters,suspended_until,last_success_at,last_failure_at FROM "+stateTable()+" WHERE connector_key=?";try(PreparedStatement st=c.prepareStatement(q)){st.setString(1,key.value());try(ResultSet rs=st.executeQuery()){if(!rs.next())return new ConnectorRuntimeState(key,0,null,null,null);return new ConnectorRuntimeState(key,rs.getInt(1),JdbcTemporal.readNullable(rs,"suspended_until"),JdbcTemporal.readNullable(rs,"last_success_at"),JdbcTemporal.readNullable(rs,"last_failure_at"));}}}
    private void upsertSuccess(Connection c,ConnectorKey key,Instant at)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL){try(PreparedStatement st=c.prepareStatement("INSERT INTO infranexum_integrations.connector_runtime_state(connector_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at) VALUES(?,0,NULL,?,NULL,?) ON CONFLICT(connector_key) DO UPDATE SET consecutive_dead_letters=0,suspended_until=NULL,last_success_at=EXCLUDED.last_success_at,updated_at=EXCLUDED.updated_at")){st.setString(1,key.value());JdbcTemporal.bindInstant(st,2,at);JdbcTemporal.bindInstant(st,3,at);st.executeUpdate();}}else{mergeState(c,key,0,null,at,null,at);}}
    private void upsertResume(Connection c,ConnectorKey key,Instant at)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL){try(PreparedStatement st=c.prepareStatement("INSERT INTO infranexum_integrations.connector_runtime_state(connector_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at) VALUES(?,0,NULL,NULL,NULL,?) ON CONFLICT(connector_key) DO UPDATE SET consecutive_dead_letters=0,suspended_until=NULL,updated_at=EXCLUDED.updated_at")){st.setString(1,key.value());JdbcTemporal.bindInstant(st,2,at);st.executeUpdate();}}else{ConnectorRuntimeState current=readRuntimeState(c,key);mergeState(c,key,0,null,current.lastSuccessAt(),current.lastFailureAt(),at);}}
    private void upsertDeadLetter(Connection connection, ConnectorKey key, Instant failedAt, int threshold, Duration duration) throws SQLException {
        Instant suspensionUntil = failedAt.plus(duration);
        if (dialect == JdbcDatabaseDialect.POSTGRESQL) {
            String statementSql = """
                    INSERT INTO infranexum_integrations.connector_runtime_state(
                        connector_key, consecutive_dead_letters, suspended_until, last_success_at, last_failure_at, updated_at)
                    VALUES (?, 1, CASE WHEN ? <= 1 THEN ? ELSE NULL END, NULL, ?, ?)
                    ON CONFLICT(connector_key) DO UPDATE SET
                        consecutive_dead_letters = infranexum_integrations.connector_runtime_state.consecutive_dead_letters + 1,
                        suspended_until = CASE
                            WHEN infranexum_integrations.connector_runtime_state.consecutive_dead_letters + 1 >= ? THEN ?
                            ELSE infranexum_integrations.connector_runtime_state.suspended_until
                        END,
                        last_failure_at = EXCLUDED.last_failure_at,
                        updated_at = EXCLUDED.updated_at
                    """;
            try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                statement.setString(1, key.value());
                statement.setInt(2, threshold);
                JdbcTemporal.bindInstant(statement, 3, suspensionUntil);
                JdbcTemporal.bindInstant(statement, 4, failedAt);
                JdbcTemporal.bindInstant(statement, 5, failedAt);
                statement.setInt(6, threshold);
                JdbcTemporal.bindInstant(statement, 7, suspensionUntil);
                requireSingle(statement.executeUpdate(), "update PostgreSQL connector dead-letter state");
            }
            return;
        }
        updateOracleDeadLetterState(connection, key, failedAt, threshold, suspensionUntil);
    }

    /** Updates Oracle state atomically; a savepoint closes the first-row concurrent insert race. */
    private void updateOracleDeadLetterState(
            Connection connection, ConnectorKey key, Instant failedAt, int threshold, Instant suspensionUntil) throws SQLException {
        if (incrementOracleDeadLetterState(connection, key, failedAt, threshold, suspensionUntil) == 1) return;

        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO INFRANEXUM_INTEGRATION_STATE(CONNECTOR_KEY,CONSECUTIVE_DEAD_LETTERS,SUSPENDED_UNTIL,LAST_SUCCESS_AT,LAST_FAILURE_AT,UPDATED_AT) VALUES(?,1,?,NULL,?,?)")) {
            statement.setString(1, key.value());
            JdbcTemporal.bindInstant(statement, 2, threshold <= 1 ? suspensionUntil : null);
            JdbcTemporal.bindInstant(statement, 3, failedAt);
            JdbcTemporal.bindInstant(statement, 4, failedAt);
            requireSingle(statement.executeUpdate(), "insert Oracle connector dead-letter state");
            connection.releaseSavepoint(savepoint);
        } catch (SQLException failure) {
            if (!dialect.isUniqueViolation(failure)) throw failure;
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            requireSingle(
                    incrementOracleDeadLetterState(connection, key, failedAt, threshold, suspensionUntil),
                    "retry Oracle connector dead-letter state update");
        }
    }

    private int incrementOracleDeadLetterState(
            Connection connection, ConnectorKey key, Instant failedAt, int threshold, Instant suspensionUntil) throws SQLException {
        String statementSql = """
                UPDATE INFRANEXUM_INTEGRATION_STATE
                SET CONSECUTIVE_DEAD_LETTERS=CONSECUTIVE_DEAD_LETTERS+1,
                    SUSPENDED_UNTIL=CASE WHEN CONSECUTIVE_DEAD_LETTERS+1>=? THEN ? ELSE SUSPENDED_UNTIL END,
                    LAST_FAILURE_AT=?,
                    UPDATED_AT=?
                WHERE CONNECTOR_KEY=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
            statement.setInt(1, threshold);
            JdbcTemporal.bindInstant(statement, 2, suspensionUntil);
            JdbcTemporal.bindInstant(statement, 3, failedAt);
            JdbcTemporal.bindInstant(statement, 4, failedAt);
            statement.setString(5, key.value());
            return statement.executeUpdate();
        }
    }
    private void mergeState(Connection c,ConnectorKey key,int failures,Instant suspended,Instant success,Instant failure,Instant updated)throws SQLException{String merge="MERGE INTO INFRANEXUM_INTEGRATION_STATE t USING (SELECT ? CONNECTOR_KEY FROM dual) s ON (t.CONNECTOR_KEY=s.CONNECTOR_KEY) WHEN MATCHED THEN UPDATE SET t.CONSECUTIVE_DEAD_LETTERS=?,t.SUSPENDED_UNTIL=?,t.LAST_SUCCESS_AT=?,t.LAST_FAILURE_AT=?,t.UPDATED_AT=? WHEN NOT MATCHED THEN INSERT(CONNECTOR_KEY,CONSECUTIVE_DEAD_LETTERS,SUSPENDED_UNTIL,LAST_SUCCESS_AT,LAST_FAILURE_AT,UPDATED_AT) VALUES(?,?,?,?,?,?)";try(PreparedStatement st=c.prepareStatement(merge)){st.setString(1,key.value());st.setInt(2,failures);JdbcTemporal.bindInstant(st,3,suspended);JdbcTemporal.bindInstant(st,4,success);JdbcTemporal.bindInstant(st,5,failure);JdbcTemporal.bindInstant(st,6,updated);st.setString(7,key.value());st.setInt(8,failures);JdbcTemporal.bindInstant(st,9,suspended);JdbcTemporal.bindInstant(st,10,success);JdbcTemporal.bindInstant(st,11,failure);JdbcTemporal.bindInstant(st,12,updated);st.executeUpdate();}}

    private <T>T tx(String operation,SqlWork<T> work){try(Connection c=dataSource.getConnection()){boolean old=c.getAutoCommit();if(old)c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}if(failure instanceof SQLException sqlFailure)throw new JdbcPersistenceException(operation,sqlFailure);throw failure;}finally{if(old)try{c.setAutoCommit(true);}catch(SQLException ignored){}}}catch(SQLException failure){throw new JdbcPersistenceException(operation,failure);}}
    private String table(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.connector_inbox":"INFRANEXUM_INTEGRATION_INBOX";}
    private String stateTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.connector_runtime_state":"INFRANEXUM_INTEGRATION_STATE";}
    private String sql(String pg,String oracle){return dialect==JdbcDatabaseDialect.POSTGRESQL?pg:oracle;}
    private static void boundedLimit(int limit){if(limit<1||limit>MAX_BATCH)throw new IllegalArgumentException("limit must be between 1 and 1000");}
    private static Duration positive(Duration value,String field){Objects.requireNonNull(value,field);if(value.isZero()||value.isNegative())throw new IllegalArgumentException(field+" must be positive");return value;}
    private static String required(String value,String field,int max){Objects.requireNonNull(value,field);String normalized=value.strip();if(normalized.isEmpty()||normalized.length()>max)throw new IllegalArgumentException("invalid "+field);return normalized;}
    private static void requireSingle(int count,String op){if(count!=1)throw new IllegalStateException(op+" affected "+count+" rows");}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    @FunctionalInterface private interface StatementBinder{void bind(PreparedStatement statement)throws SQLException;}
}
