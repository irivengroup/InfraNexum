package io.infranexum.organization.ports;
import io.infranexum.core.contracts.DomainIdentifier; import java.time.Instant; import java.util.Optional;
/** Bounded-context command deduplication record stored atomically with authoritative writes. */
public interface IdempotencyRepository {
    Optional<Record> find(String key); void insert(Record record);
    record Record(String key,String payloadSha256,String resourceType,DomainIdentifier resourceId,Instant createdAt) {}
}
