package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cross-context regression coverage for transaction-bound JDBC deduplication repositories. */
final class JdbcIdempotencyRepositoriesCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ID=id(90);
    private static final List<Class<?>> REPOSITORIES=List.of(
            JdbcPartnerIdempotencyRepository.class, JdbcAssetIdempotencyRepository.class,
            JdbcComplianceIdempotencyRepository.class, JdbcFacilityIdempotencyRepository.class,
            JdbcDcimPhysicalIdempotencyRepository.class, JdbcIpamIdempotencyRepository.class,
            JdbcOrganizationIdempotencyRepository.class);

    @Test void everyDomainDedupRepositoryReadsWritesMissesAndRejectsConcurrentReuse() throws Exception {
        for(Class<?> type:REPOSITORIES){
            Map<String,Object> row=row();
            var connection=connection(query(row),update(1));
            Object repository=construct(type,transaction(connection.connection()),JdbcDatabaseDialect.POSTGRESQL);
            Method find=type.getMethod("find",String.class);
            Object optional=find.invoke(repository,"key");
            Object record=((java.util.Optional<?>)optional).orElseThrow();
            Method insert=type.getMethod("insert",record.getClass());
            insert.invoke(repository,record);
            assertTrue(connection.exhausted(), type.getSimpleName()+" must execute the read and write scripts");

            var missing=connection(query(List.of()));
            Object missingRepository=construct(type,transaction(missing.connection()),JdbcDatabaseDialect.ORACLE);
            assertTrue(((java.util.Optional<?>)find.invoke(missingRepository,"missing")).isEmpty());

            var duplicate=connection(updateFailure(new SQLException("duplicate","23505",1)));
            Object duplicateRepository=construct(type,transaction(duplicate.connection()),JdbcDatabaseDialect.POSTGRESQL);
            java.lang.reflect.InvocationTargetException failure=assertThrows(java.lang.reflect.InvocationTargetException.class,
                    ()->insert.invoke(duplicateRepository,record));
            assertTrue(failure.getCause() instanceof RuntimeException);

            var zero = connection(update(0));
            Object zeroRepository=construct(type,transaction(zero.connection()),JdbcDatabaseDialect.POSTGRESQL);
            if (type == JdbcIpamIdempotencyRepository.class) {
                insert.invoke(zeroRepository,record);
            } else {
                var zeroFailure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->insert.invoke(zeroRepository,record));
                assertTrue(zeroFailure.getCause() instanceof JdbcPersistenceException);
            }

            var offline = connection(updateFailure(new SQLException("offline","08006",2)));
            Object offlineRepository=construct(type,transaction(offline.connection()),JdbcDatabaseDialect.POSTGRESQL);
            var offlineFailure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->insert.invoke(offlineRepository,record));
            assertTrue(offlineFailure.getCause() instanceof JdbcPersistenceException);
        }
    }

    @Test void constructorsAndSqlFailuresRemainFailClosed() throws Exception {
        for(Class<?> type:REPOSITORIES){
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->construct(type,null,JdbcDatabaseDialect.POSTGRESQL));
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->construct(type,transaction(connection().connection()),null));
            var failed=connection(queryFailure(new SQLException("offline","08006")));
            Object repository=construct(type,transaction(failed.connection()),JdbcDatabaseDialect.POSTGRESQL);
            Method find=type.getMethod("find",String.class);
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->find.invoke(repository,"key"));
            assertTrue(error.getCause() instanceof JdbcPersistenceException);
        }
    }

    private static Object construct(Class<?> type,JdbcConnectionAccess access,JdbcDatabaseDialect dialect) throws Exception {
        Constructor<?> constructor=type.getConstructor(JdbcConnectionAccess.class,JdbcDatabaseDialect.class);
        return constructor.newInstance(access,dialect);
    }
    private static Map<String,Object> row(){var r=new LinkedHashMap<String,Object>();r.put("idempotency_key","key");r.put("payload_sha256","a".repeat(64));r.put("operation_name","create");r.put("operation","create");r.put("record_type","warranty");r.put("record_id",ID.value());r.put("result_id",ID.value());r.put("partner_id",ID.value());r.put("asset_id",ID.value());r.put("facility_id",ID.value());r.put("resource_type","organization");r.put("resource_id",ID.value());r.put("created_at",T);return r;}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
