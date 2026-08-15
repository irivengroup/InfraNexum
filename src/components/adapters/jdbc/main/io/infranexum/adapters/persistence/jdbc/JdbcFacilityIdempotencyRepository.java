package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.ports.FacilityIdempotencyRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Transaction-bound idempotency store for PGM-07-E04 DCIM facility mutations. */
public final class JdbcFacilityIdempotencyRepository implements FacilityIdempotencyRepository {
    private final JdbcConnectionAccess transaction; private final JdbcDatabaseDialect dialect;
    public JdbcFacilityIdempotencyRepository(JdbcConnectionAccess transaction,JdbcDatabaseDialect dialect){this.transaction=Objects.requireNonNull(transaction,"transaction");this.dialect=Objects.requireNonNull(dialect,"dialect");}
    @Override public Optional<Record> find(String key){Objects.requireNonNull(key,"key");String sql="SELECT idempotency_key,payload_sha256,operation_name,facility_id,created_at FROM "+table()+" WHERE idempotency_key=?";try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement(sql)){s.setString(1,key);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new Record(r.getString("idempotency_key"),r.getString("payload_sha256"),r.getString("operation_name"),dialect.readIdentifier(r,"facility_id"),JdbcTemporal.readRequired(r,"created_at")));}}catch(SQLException failure){throw fail("find DCIM facility idempotency key",failure);}}
    @Override public void insert(Record record){Objects.requireNonNull(record,"record");String sql="INSERT INTO "+table()+" (idempotency_key,payload_sha256,operation_name,facility_id,created_at) VALUES (?,?,?,?,?)";try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement(sql)){s.setString(1,record.key());s.setString(2,record.payloadSha256());s.setString(3,record.operation());dialect.bindIdentifier(s,4,record.facilityId());JdbcTemporal.bindInstant(s,5,record.createdAt());if(s.executeUpdate()!=1)throw new SQLException("facility idempotency insert affected unexpected rows");}catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw new FacilityConflictException("IDEMPOTENCY_CONFLICT","idempotency key was committed concurrently");throw fail("insert DCIM facility idempotency key",failure);}}
    private String table(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_dcim.facility_command_dedup":"INFRANEXUM_DCIM_FAC_DEDUP";} private static JdbcPersistenceException fail(String op,SQLException failure){return new JdbcPersistenceException(op,failure);}
}
