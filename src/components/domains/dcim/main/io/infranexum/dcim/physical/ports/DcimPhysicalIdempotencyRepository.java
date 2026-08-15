package io.infranexum.dcim.physical.ports;
import io.infranexum.core.contracts.DomainIdentifier; import java.time.Instant; import java.util.Optional;
/** Durable idempotency records for DCIM physical mutations. */
public interface DcimPhysicalIdempotencyRepository {
 record Record(String key,String payloadSha256,String operation,DomainIdentifier resultId,Instant createdAt){}
 Optional<Record> find(String key); void insert(Record record);
}
