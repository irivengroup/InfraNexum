package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.dcim.physical.domain.DcimPhysicalConflictException;
import io.infranexum.dcim.physical.ports.DcimPhysicalIdempotencyRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Transaction-bound PGM-07-E05 idempotency store. */
public final class JdbcDcimPhysicalIdempotencyRepository implements DcimPhysicalIdempotencyRepository {
    private final JdbcConnectionAccess transaction; private final JdbcDatabaseDialect dialect;
    public JdbcDcimPhysicalIdempotencyRepository(JdbcConnectionAccess transaction,JdbcDatabaseDialect dialect){this.transaction=Objects.requireNonNull(transaction,"transaction");this.dialect=Objects.requireNonNull(dialect,"dialect");}
    @Override public Optional<Record> find(String key){Objects.requireNonNull(key,"key");try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement("SELECT idempotency_key,payload_sha256,operation_name,result_id,created_at FROM "+table()+" WHERE idempotency_key=?")){s.setString(1,key);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new Record(r.getString(1),r.getString(2),r.getString(3),dialect.readIdentifier(r,"result_id"),JdbcTemporal.readRequired(r,"created_at")));}}catch(SQLException e){throw fail("find DCIM physical idempotency",e);}}
    @Override public void insert(Record r){Objects.requireNonNull(r,"record");try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement("INSERT INTO "+table()+" (idempotency_key,payload_sha256,operation_name,result_id,created_at) VALUES (?,?,?,?,?)")){s.setString(1,r.key());s.setString(2,r.payloadSha256());s.setString(3,r.operation());dialect.bindIdentifier(s,4,r.resultId());JdbcTemporal.bindInstant(s,5,r.createdAt());if(s.executeUpdate()!=1)throw new SQLException("unexpected physical idempotency row count");}catch(SQLException e){if(dialect.isUniqueViolation(e))throw new DcimPhysicalConflictException("IDEMPOTENCY_CONFLICT","idempotency key was committed concurrently");throw fail("insert DCIM physical idempotency",e);}}
    private String table(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_dcim.physical_command_dedup":"INFRANEXUM_DCIM_PHYS_DEDUP";} private static JdbcPersistenceException fail(String op,SQLException e){return new JdbcPersistenceException(op,e);}
}
