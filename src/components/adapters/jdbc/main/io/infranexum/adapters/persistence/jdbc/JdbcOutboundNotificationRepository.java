package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.OutboundNotificationAdmission;
import io.infranexum.integrations.OutboundNotificationAdmissionOutcome;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationNotFoundException;
import io.infranexum.integrations.OutboundNotificationRepository;
import io.infranexum.integrations.OutboundNotificationRuntimeState;
import io.infranexum.integrations.OutboundNotificationStateConflictException;
import io.infranexum.integrations.OutboundNotificationStatus;
import io.infranexum.integrations.OutboundNotificationTransportException;
import java.nio.charset.StandardCharsets;
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

/** JDBC outbound notification outbox/DLQ with PostgreSQL/Oracle parity and lease fencing. */
public final class JdbcOutboundNotificationRepository implements OutboundNotificationRepository {
    private static final int MAX_BATCH = 1_000;
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcOutboundNotificationRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public OutboundNotificationAdmissionOutcome admit(OutboundNotificationAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        return tx("admit outbound notification", connection -> {
            if (insertAdmission(connection, admission)) {
                return new OutboundNotificationAdmissionOutcome(readById(connection, admission.deliveryId()), false);
            }
            OutboundNotificationDelivery existing = readByNaturalKey(connection, admission.endpointKey(), admission.eventId());
            if (!existing.payloadSha256().equals(admission.payloadSha256()) || !existing.eventType().equals(admission.eventType())) {
                throw new DuplicateDeliveryConflictException("notification event identifier was reused with different content");
            }
            return new OutboundNotificationAdmissionOutcome(existing, true);
        });
    }

    @Override
    public List<OutboundNotificationDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
        String worker = required(workerId, "workerId", 160);
        boundedLimit(limit);
        Objects.requireNonNull(now, "now"); positive(leaseDuration, "leaseDuration");
        return tx("claim outbound notifications", c -> dialect == JdbcDatabaseDialect.POSTGRESQL
                ? claimPostgresql(c, worker, limit, now, now.plus(leaseDuration))
                : claimOracle(c, worker, limit, now, now.plus(leaseDuration)));
    }

    @Override
    public void markDelivered(DomainIdentifier deliveryId, String workerId, Instant deliveredAt) {
        Objects.requireNonNull(deliveryId, "deliveryId"); String worker = required(workerId, "workerId", 160); Objects.requireNonNull(deliveredAt, "deliveredAt");
        tx("deliver outbound notification", connection -> {
            OutboundNotificationDelivery current = requireLease(connection, deliveryId, worker);
            try (PreparedStatement st = connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.notification_outbox SET status='DELIVERED',delivered_at=?,lease_owner=NULL,lease_until=NULL,last_failure=NULL,updated_at=? WHERE delivery_id=? AND status='IN_FLIGHT' AND lease_owner=?",
                    "UPDATE INFRANEXUM_NOTIFICATION_OUTBOX SET STATUS='DELIVERED',DELIVERED_AT=?,LEASE_OWNER=NULL,LEASE_UNTIL=NULL,LAST_FAILURE=NULL,UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='IN_FLIGHT' AND LEASE_OWNER=?"))) {
                JdbcTemporal.bindInstant(st,1,deliveredAt); JdbcTemporal.bindInstant(st,2,deliveredAt); dialect.bindIdentifier(st,3,deliveryId); st.setString(4,worker);
                requireSingle(st.executeUpdate(), "deliver outbound notification");
            }
            upsertSuccess(connection, current.endpointKey(), deliveredAt);
            return null;
        });
    }

    @Override
    public OutboundNotificationStatus markFailed(
            DomainIdentifier deliveryId, String workerId, Instant failedAt, RetryPolicy retryPolicy,
            Throwable failure, boolean retryable, int suspendAfterDeadLetters, Duration suspensionDuration) {
        Objects.requireNonNull(deliveryId,"deliveryId"); String worker=required(workerId,"workerId",160); Objects.requireNonNull(failedAt,"failedAt");
        Objects.requireNonNull(retryPolicy,"retryPolicy"); Objects.requireNonNull(failure,"failure"); positive(suspensionDuration,"suspensionDuration");
        if (suspendAfterDeadLetters<1||suspendAfterDeadLetters>100) throw new IllegalArgumentException("suspendAfterDeadLetters must be between 1 and 100");
        return tx("fail outbound notification", connection -> {
            OutboundNotificationDelivery current=requireLease(connection,deliveryId,worker);
            boolean dead=!retryable || current.attempts()>=retryPolicy.maximumAttempts();
            OutboundNotificationStatus next=dead?OutboundNotificationStatus.DEAD_LETTER:OutboundNotificationStatus.PENDING;
            Instant available=dead?failedAt:failedAt.plus(retryPolicy.delayAfterFailure(current.attempts()));
            String failureCode=failure instanceof OutboundNotificationTransportException transport ? transport.code() : failure.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]","_");
            if(failureCode.length()>64) failureCode=failureCode.substring(0,64);
            try(PreparedStatement st=connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.notification_outbox SET status=?,available_at=?,lease_owner=NULL,lease_until=NULL,last_failure=?,updated_at=? WHERE delivery_id=? AND status='IN_FLIGHT' AND lease_owner=?",
                    "UPDATE INFRANEXUM_NOTIFICATION_OUTBOX SET STATUS=?,AVAILABLE_AT=?,LEASE_OWNER=NULL,LEASE_UNTIL=NULL,LAST_FAILURE=?,UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='IN_FLIGHT' AND LEASE_OWNER=?"))){
                st.setString(1,next.name());JdbcTemporal.bindInstant(st,2,available);st.setString(3,failureCode);JdbcTemporal.bindInstant(st,4,failedAt);dialect.bindIdentifier(st,5,deliveryId);st.setString(6,worker);requireSingle(st.executeUpdate(),"fail outbound notification");
            }
            if(dead) upsertDeadLetter(connection,current.endpointKey(),failedAt,suspendAfterDeadLetters,suspensionDuration);
            return next;
        });
    }

    @Override
    public List<OutboundNotificationDelivery> listDeadLetters(ConnectorKey endpointKey, int offset, int limit) {
        if(offset<0||offset>1_000_000) throw new IllegalArgumentException("offset must be between 0 and 1000000"); boundedLimit(limit);
        return tx("list notification dead letters", connection -> {
            String predicate=endpointKey==null?"":" AND endpoint_key=?";
            String query=dialect==JdbcDatabaseDialect.POSTGRESQL
                    ?"SELECT * FROM infranexum_integrations.notification_outbox WHERE status='DEAD_LETTER'"+predicate+" ORDER BY created_at,delivery_id LIMIT ? OFFSET ?"
                    :"SELECT * FROM INFRANEXUM_NOTIFICATION_OUTBOX WHERE STATUS='DEAD_LETTER'"+(endpointKey==null?"":" AND ENDPOINT_KEY=?")+" ORDER BY CREATED_AT,DELIVERY_ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            try(PreparedStatement st=connection.prepareStatement(query)){int i=1;if(endpointKey!=null)st.setString(i++,endpointKey.value());if(dialect==JdbcDatabaseDialect.POSTGRESQL){st.setInt(i++,limit);st.setInt(i,offset);}else{st.setInt(i++,offset);st.setInt(i,limit);}try(ResultSet rs=st.executeQuery()){return readAll(rs);}}
        });
    }

    @Override
    public OutboundNotificationDelivery replay(DomainIdentifier deliveryId, Instant replayedAt) {
        Objects.requireNonNull(deliveryId,"deliveryId");Objects.requireNonNull(replayedAt,"replayedAt");
        return tx("replay outbound notification", connection -> {
            OutboundNotificationDelivery current=readByIdForUpdate(connection,deliveryId);
            if(current.status()!=OutboundNotificationStatus.DEAD_LETTER) throw new OutboundNotificationStateConflictException("only DEAD_LETTER notifications may be replayed");
            try(PreparedStatement st=connection.prepareStatement(sql(
                    "UPDATE infranexum_integrations.notification_outbox SET status='PENDING',attempts=0,available_at=?,lease_owner=NULL,lease_until=NULL,delivered_at=NULL,last_failure=NULL,replay_count=replay_count+1,last_replayed_at=?,updated_at=? WHERE delivery_id=? AND status='DEAD_LETTER'",
                    "UPDATE INFRANEXUM_NOTIFICATION_OUTBOX SET STATUS='PENDING',ATTEMPTS=0,AVAILABLE_AT=?,LEASE_OWNER=NULL,LEASE_UNTIL=NULL,DELIVERED_AT=NULL,LAST_FAILURE=NULL,REPLAY_COUNT=REPLAY_COUNT+1,LAST_REPLAYED_AT=?,UPDATED_AT=? WHERE DELIVERY_ID=? AND STATUS='DEAD_LETTER'"))){JdbcTemporal.bindInstant(st,1,replayedAt);JdbcTemporal.bindInstant(st,2,replayedAt);JdbcTemporal.bindInstant(st,3,replayedAt);dialect.bindIdentifier(st,4,deliveryId);requireSingle(st.executeUpdate(),"replay outbound notification");}
            return readById(connection,deliveryId);
        });
    }

    @Override public OutboundNotificationRuntimeState runtimeState(ConnectorKey endpointKey){Objects.requireNonNull(endpointKey,"endpointKey");return tx("read notification runtime state",c->readRuntimeState(c,endpointKey));}
    @Override public OutboundNotificationRuntimeState resume(ConnectorKey endpointKey,Instant resumedAt){Objects.requireNonNull(endpointKey,"endpointKey");Objects.requireNonNull(resumedAt,"resumedAt");return tx("resume notification endpoint",c->{upsertResume(c,endpointKey,resumedAt);return readRuntimeState(c,endpointKey);});}
    @Override public long backlogSize(ConnectorKey endpointKey,Instant now){Objects.requireNonNull(endpointKey,"endpointKey");Objects.requireNonNull(now,"now");return count("notification backlog",endpointKey,"status IN ('PENDING','IN_FLIGHT')");}
    @Override public long deadLetterCount(ConnectorKey endpointKey){Objects.requireNonNull(endpointKey,"endpointKey");return count("notification dead letters",endpointKey,"status='DEAD_LETTER'");}

    private boolean insertAdmission(Connection c,OutboundNotificationAdmission a)throws SQLException{
        String q=sql(
                "INSERT INTO infranexum_integrations.notification_outbox(delivery_id,endpoint_key,event_id,event_type,payload_json,payload_sha256,status,attempts,available_at,lease_owner,lease_until,delivered_at,last_failure,replay_count,last_replayed_at,created_at,updated_at) VALUES(?,?,?,?,CAST(? AS JSONB),?,'PENDING',0,?,NULL,NULL,NULL,NULL,0,NULL,?,?) ON CONFLICT(endpoint_key,event_id) DO NOTHING",
                "INSERT INTO INFRANEXUM_NOTIFICATION_OUTBOX(DELIVERY_ID,ENDPOINT_KEY,EVENT_ID,EVENT_TYPE,PAYLOAD_JSON,PAYLOAD_SHA256,STATUS,ATTEMPTS,AVAILABLE_AT,LEASE_OWNER,LEASE_UNTIL,DELIVERED_AT,LAST_FAILURE,REPLAY_COUNT,LAST_REPLAYED_AT,CREATED_AT,UPDATED_AT) VALUES(?,?,?,?,?,?,'PENDING',0,?,NULL,NULL,NULL,NULL,0,NULL,?,?)");
        Savepoint sp=dialect==JdbcDatabaseDialect.ORACLE?c.setSavepoint():null;
        try(PreparedStatement st=c.prepareStatement(q)){dialect.bindIdentifier(st,1,a.deliveryId());st.setString(2,a.endpointKey().value());st.setString(3,a.eventId());st.setString(4,a.eventType());dialect.bindJson(st,5,new String(a.payload(),StandardCharsets.UTF_8));st.setString(6,a.payloadSha256());JdbcTemporal.bindInstant(st,7,a.createdAt());JdbcTemporal.bindInstant(st,8,a.createdAt());JdbcTemporal.bindInstant(st,9,a.createdAt());boolean inserted=st.executeUpdate()==1;if(sp!=null)c.releaseSavepoint(sp);return inserted;}catch(SQLException failure){if(sp!=null&&dialect.isUniqueViolation(failure)){c.rollback(sp);c.releaseSavepoint(sp);return false;}throw failure;}
    }

    private List<OutboundNotificationDelivery> claimPostgresql(Connection c,String worker,int limit,Instant now,Instant leaseUntil)throws SQLException{
        String q="""
                WITH candidates AS (
                  SELECT n.delivery_id FROM infranexum_integrations.notification_outbox n
                  LEFT JOIN infranexum_integrations.notification_endpoint_state s ON s.endpoint_key=n.endpoint_key
                  WHERE ((n.status='PENDING' AND n.available_at<=?) OR (n.status='IN_FLIGHT' AND n.lease_until<=?))
                    AND (s.suspended_until IS NULL OR s.suspended_until<=?)
                  ORDER BY n.available_at,n.created_at,n.delivery_id LIMIT ? FOR UPDATE OF n SKIP LOCKED
                )
                UPDATE infranexum_integrations.notification_outbox n SET status='IN_FLIGHT',attempts=n.attempts+1,lease_owner=?,lease_until=?,updated_at=?
                FROM candidates WHERE n.delivery_id=candidates.delivery_id RETURNING n.*
                """;
        try(PreparedStatement st=c.prepareStatement(q)){JdbcTemporal.bindInstant(st,1,now);JdbcTemporal.bindInstant(st,2,now);JdbcTemporal.bindInstant(st,3,now);st.setInt(4,limit);st.setString(5,worker);JdbcTemporal.bindInstant(st,6,leaseUntil);JdbcTemporal.bindInstant(st,7,now);try(ResultSet rs=st.executeQuery()){return readAll(rs);}}
    }

    private List<OutboundNotificationDelivery> claimOracle(Connection c,String worker,int limit,Instant now,Instant leaseUntil)throws SQLException{
        String q="SELECT n.* FROM INFRANEXUM_NOTIFICATION_OUTBOX n LEFT JOIN INFRANEXUM_NOTIFICATION_STATE s ON s.ENDPOINT_KEY=n.ENDPOINT_KEY WHERE ((n.STATUS='PENDING' AND n.AVAILABLE_AT<=?) OR (n.STATUS='IN_FLIGHT' AND n.LEASE_UNTIL<=?)) AND (s.SUSPENDED_UNTIL IS NULL OR s.SUSPENDED_UNTIL<=?) ORDER BY n.AVAILABLE_AT,n.CREATED_AT,n.DELIVERY_ID FOR UPDATE OF n.DELIVERY_ID SKIP LOCKED";
        List<OutboundNotificationDelivery> selected;try(PreparedStatement st=c.prepareStatement(q)){JdbcTemporal.bindInstant(st,1,now);JdbcTemporal.bindInstant(st,2,now);JdbcTemporal.bindInstant(st,3,now);st.setMaxRows(limit);try(ResultSet rs=st.executeQuery()){selected=readAll(rs);}}
        List<OutboundNotificationDelivery> claimed=new ArrayList<>();String update="UPDATE INFRANEXUM_NOTIFICATION_OUTBOX SET STATUS='IN_FLIGHT',ATTEMPTS=ATTEMPTS+1,LEASE_OWNER=?,LEASE_UNTIL=?,UPDATED_AT=? WHERE DELIVERY_ID=?";
        try(PreparedStatement st=c.prepareStatement(update)){for(OutboundNotificationDelivery item:selected){st.setString(1,worker);JdbcTemporal.bindInstant(st,2,leaseUntil);JdbcTemporal.bindInstant(st,3,now);dialect.bindIdentifier(st,4,item.deliveryId());requireSingle(st.executeUpdate(),"claim Oracle outbound notification");claimed.add(new OutboundNotificationDelivery(item.deliveryId(),item.endpointKey(),item.eventId(),item.eventType(),item.payload(),item.payloadSha256(),OutboundNotificationStatus.IN_FLIGHT,item.attempts()+1,item.createdAt(),item.availableAt(),worker,leaseUntil,null,item.lastFailure(),item.replayCount(),item.lastReplayedAt()));}}
        return List.copyOf(claimed);
    }

    private OutboundNotificationDelivery requireLease(Connection c,DomainIdentifier id,String worker)throws SQLException{OutboundNotificationDelivery current=readByIdForUpdate(c,id);if(current.status()!=OutboundNotificationStatus.IN_FLIGHT||!worker.equals(current.leaseOwner()))throw new IllegalStateException("outbound notification is not leased by worker "+worker);return current;}
    private OutboundNotificationDelivery readById(Connection c,DomainIdentifier id)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE delivery_id=?",st->dialect.bindIdentifier(st,1,id),"unknown outbound notification: "+id);}
    private OutboundNotificationDelivery readByIdForUpdate(Connection c,DomainIdentifier id)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE delivery_id=? FOR UPDATE",st->dialect.bindIdentifier(st,1,id),"unknown outbound notification: "+id);}
    private OutboundNotificationDelivery readByNaturalKey(Connection c,ConnectorKey key,String eventId)throws SQLException{return readOne(c,"SELECT * FROM "+table()+" WHERE endpoint_key=? AND event_id=?",st->{st.setString(1,key.value());st.setString(2,eventId);},"notification disappeared after uniqueness conflict");}
    private OutboundNotificationDelivery readOne(Connection c,String q,StatementBinder binder,String missing)throws SQLException{try(PreparedStatement st=c.prepareStatement(q)){binder.bind(st);try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new OutboundNotificationNotFoundException(missing);return read(rs);}}}
    private List<OutboundNotificationDelivery> readAll(ResultSet rs)throws SQLException{List<OutboundNotificationDelivery> out=new ArrayList<>();while(rs.next())out.add(read(rs));return List.copyOf(out);}
    private OutboundNotificationDelivery read(ResultSet rs)throws SQLException{return new OutboundNotificationDelivery(dialect.readIdentifier(rs,"delivery_id"),new ConnectorKey(rs.getString("endpoint_key")),rs.getString("event_id"),rs.getString("event_type"),rs.getString("payload_json").getBytes(StandardCharsets.UTF_8),rs.getString("payload_sha256"),OutboundNotificationStatus.valueOf(rs.getString("status")),rs.getInt("attempts"),JdbcTemporal.readRequired(rs,"created_at"),JdbcTemporal.readRequired(rs,"available_at"),rs.getString("lease_owner"),JdbcTemporal.readNullable(rs,"lease_until"),JdbcTemporal.readNullable(rs,"delivered_at"),rs.getString("last_failure"),rs.getInt("replay_count"),JdbcTemporal.readNullable(rs,"last_replayed_at"));}

    private OutboundNotificationRuntimeState readRuntimeState(Connection c,ConnectorKey key)throws SQLException{String q="SELECT consecutive_dead_letters,suspended_until,last_success_at,last_failure_at FROM "+stateTable()+" WHERE endpoint_key=?";try(PreparedStatement st=c.prepareStatement(q)){st.setString(1,key.value());try(ResultSet rs=st.executeQuery()){if(!rs.next())return new OutboundNotificationRuntimeState(key,0,null,null,null);return new OutboundNotificationRuntimeState(key,rs.getInt(1),JdbcTemporal.readNullable(rs,"suspended_until"),JdbcTemporal.readNullable(rs,"last_success_at"),JdbcTemporal.readNullable(rs,"last_failure_at"));}}}
    private void upsertSuccess(Connection c,ConnectorKey key,Instant at)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL){try(PreparedStatement st=c.prepareStatement("INSERT INTO infranexum_integrations.notification_endpoint_state(endpoint_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at) VALUES(?,0,NULL,?,NULL,?) ON CONFLICT(endpoint_key) DO UPDATE SET consecutive_dead_letters=0,suspended_until=NULL,last_success_at=EXCLUDED.last_success_at,updated_at=EXCLUDED.updated_at")){st.setString(1,key.value());JdbcTemporal.bindInstant(st,2,at);JdbcTemporal.bindInstant(st,3,at);st.executeUpdate();}}else{mergeState(c,key,0,null,at,null,at);}}
    private void upsertResume(Connection c,ConnectorKey key,Instant at)throws SQLException{if(dialect==JdbcDatabaseDialect.POSTGRESQL){try(PreparedStatement st=c.prepareStatement("INSERT INTO infranexum_integrations.notification_endpoint_state(endpoint_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at) VALUES(?,0,NULL,NULL,NULL,?) ON CONFLICT(endpoint_key) DO UPDATE SET consecutive_dead_letters=0,suspended_until=NULL,updated_at=EXCLUDED.updated_at")){st.setString(1,key.value());JdbcTemporal.bindInstant(st,2,at);st.executeUpdate();}}else{OutboundNotificationRuntimeState cur=readRuntimeState(c,key);mergeState(c,key,0,null,cur.lastSuccessAt(),cur.lastFailureAt(),at);}}
    private void upsertDeadLetter(Connection c,ConnectorKey key,Instant failedAt,int threshold,Duration duration)throws SQLException{
        Instant until=failedAt.plus(duration);
        if(dialect==JdbcDatabaseDialect.POSTGRESQL){
            String q = """
                    INSERT INTO infranexum_integrations.notification_endpoint_state(
                        endpoint_key,consecutive_dead_letters,suspended_until,last_success_at,last_failure_at,updated_at
                    ) VALUES(?,1,CASE WHEN ?<=1 THEN ? ELSE NULL END,NULL,?,?)
                    ON CONFLICT(endpoint_key) DO UPDATE SET
                        consecutive_dead_letters=infranexum_integrations.notification_endpoint_state.consecutive_dead_letters+1,
                        suspended_until=CASE
                            WHEN infranexum_integrations.notification_endpoint_state.consecutive_dead_letters+1>=? THEN ?
                            ELSE infranexum_integrations.notification_endpoint_state.suspended_until
                        END,
                        last_failure_at=EXCLUDED.last_failure_at,
                        updated_at=EXCLUDED.updated_at
                    """;
            try(PreparedStatement st=c.prepareStatement(q)){
                st.setString(1,key.value());
                st.setInt(2,threshold);
                JdbcTemporal.bindInstant(st,3,until);
                JdbcTemporal.bindInstant(st,4,failedAt);
                JdbcTemporal.bindInstant(st,5,failedAt);
                st.setInt(6,threshold);
                JdbcTemporal.bindInstant(st,7,until);
                requireSingle(st.executeUpdate(),"update PostgreSQL notification dead-letter state");
            }
            return;
        }
        updateOracleDeadLetter(c,key,failedAt,threshold,until);
    }
    private void updateOracleDeadLetter(Connection c,ConnectorKey key,Instant failedAt,int threshold,Instant until)throws SQLException{if(incrementOracleDeadLetter(c,key,failedAt,threshold,until)==1)return;Savepoint sp=c.setSavepoint();try(PreparedStatement st=c.prepareStatement("INSERT INTO INFRANEXUM_NOTIFICATION_STATE(ENDPOINT_KEY,CONSECUTIVE_DEAD_LETTERS,SUSPENDED_UNTIL,LAST_SUCCESS_AT,LAST_FAILURE_AT,UPDATED_AT) VALUES(?,1,?,NULL,?,?)")){st.setString(1,key.value());JdbcTemporal.bindInstant(st,2,threshold<=1?until:null);JdbcTemporal.bindInstant(st,3,failedAt);JdbcTemporal.bindInstant(st,4,failedAt);requireSingle(st.executeUpdate(),"insert Oracle notification dead-letter state");c.releaseSavepoint(sp);}catch(SQLException failure){if(!dialect.isUniqueViolation(failure))throw failure;c.rollback(sp);c.releaseSavepoint(sp);requireSingle(incrementOracleDeadLetter(c,key,failedAt,threshold,until),"retry Oracle notification state update");}}
    private int incrementOracleDeadLetter(Connection c,ConnectorKey key,Instant failedAt,int threshold,Instant until)throws SQLException{try(PreparedStatement st=c.prepareStatement("UPDATE INFRANEXUM_NOTIFICATION_STATE SET CONSECUTIVE_DEAD_LETTERS=CONSECUTIVE_DEAD_LETTERS+1,SUSPENDED_UNTIL=CASE WHEN CONSECUTIVE_DEAD_LETTERS+1>=? THEN ? ELSE SUSPENDED_UNTIL END,LAST_FAILURE_AT=?,UPDATED_AT=? WHERE ENDPOINT_KEY=?")){st.setInt(1,threshold);JdbcTemporal.bindInstant(st,2,until);JdbcTemporal.bindInstant(st,3,failedAt);JdbcTemporal.bindInstant(st,4,failedAt);st.setString(5,key.value());return st.executeUpdate();}}
    private void mergeState(Connection c,ConnectorKey key,int failures,Instant suspended,Instant success,Instant failure,Instant updated)throws SQLException{String q="MERGE INTO INFRANEXUM_NOTIFICATION_STATE t USING (SELECT ? ENDPOINT_KEY FROM dual) s ON (t.ENDPOINT_KEY=s.ENDPOINT_KEY) WHEN MATCHED THEN UPDATE SET t.CONSECUTIVE_DEAD_LETTERS=?,t.SUSPENDED_UNTIL=?,t.LAST_SUCCESS_AT=?,t.LAST_FAILURE_AT=?,t.UPDATED_AT=? WHEN NOT MATCHED THEN INSERT(ENDPOINT_KEY,CONSECUTIVE_DEAD_LETTERS,SUSPENDED_UNTIL,LAST_SUCCESS_AT,LAST_FAILURE_AT,UPDATED_AT) VALUES(?,?,?,?,?,?)";try(PreparedStatement st=c.prepareStatement(q)){st.setString(1,key.value());st.setInt(2,failures);JdbcTemporal.bindInstant(st,3,suspended);JdbcTemporal.bindInstant(st,4,success);JdbcTemporal.bindInstant(st,5,failure);JdbcTemporal.bindInstant(st,6,updated);st.setString(7,key.value());st.setInt(8,failures);JdbcTemporal.bindInstant(st,9,suspended);JdbcTemporal.bindInstant(st,10,success);JdbcTemporal.bindInstant(st,11,failure);JdbcTemporal.bindInstant(st,12,updated);st.executeUpdate();}}
    private long count(String operation,ConnectorKey key,String predicate){return tx(operation,c->{try(PreparedStatement st=c.prepareStatement("SELECT COUNT(*) FROM "+table()+" WHERE endpoint_key=? AND "+predicate)){st.setString(1,key.value());try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new SQLException("count query returned no row");return rs.getLong(1);}}});}

    private <T>T tx(String operation,SqlWork<T> work){try(Connection c=dataSource.getConnection()){boolean old=c.getAutoCommit();if(old)c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}if(failure instanceof SQLException sql)throw new JdbcPersistenceException(operation,sql);throw failure;}finally{if(old)try{c.setAutoCommit(true);}catch(SQLException ignored){}}}catch(SQLException failure){throw new JdbcPersistenceException(operation,failure);}}
    private String table(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.notification_outbox":"INFRANEXUM_NOTIFICATION_OUTBOX";}
    private String stateTable(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_integrations.notification_endpoint_state":"INFRANEXUM_NOTIFICATION_STATE";}
    private String sql(String pg,String oracle){return dialect==JdbcDatabaseDialect.POSTGRESQL?pg:oracle;}
    private static void boundedLimit(int limit){if(limit<1||limit>MAX_BATCH)throw new IllegalArgumentException("limit must be between 1 and 1000");}
    private static Duration positive(Duration value,String field){Objects.requireNonNull(value,field);if(value.isZero()||value.isNegative())throw new IllegalArgumentException(field+" must be positive");return value;}
    private static String required(String value,String field,int max){Objects.requireNonNull(value,field);String normalized=value.strip();if(normalized.isEmpty()||normalized.length()>max)throw new IllegalArgumentException("invalid "+field);return normalized;}
    private static void requireSingle(int count,String operation){if(count!=1)throw new IllegalStateException(operation+" affected "+count+" rows");}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    @FunctionalInterface private interface StatementBinder{void bind(PreparedStatement statement)throws SQLException;}
}
