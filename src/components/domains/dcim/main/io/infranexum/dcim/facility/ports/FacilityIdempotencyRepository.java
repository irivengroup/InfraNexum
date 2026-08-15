package io.infranexum.dcim.facility.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Optional;

/** Deduplication port for DCIM mutations. */
public interface FacilityIdempotencyRepository {
    record Record(String key, String payloadSha256, String operation, DomainIdentifier facilityId, Instant createdAt) {}
    Optional<Record> find(String key);
    void insert(Record record);
}
