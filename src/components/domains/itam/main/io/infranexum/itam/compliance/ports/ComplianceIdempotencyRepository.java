package io.infranexum.itam.compliance.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Optional;

/** Shared idempotency ledger for contractual mutations. */
public interface ComplianceIdempotencyRepository {
    record Record(String key,String payloadSha256,String operation,String recordType,DomainIdentifier recordId,Instant createdAt) {}
    Optional<Record> find(String key); void insert(Record record);
}
