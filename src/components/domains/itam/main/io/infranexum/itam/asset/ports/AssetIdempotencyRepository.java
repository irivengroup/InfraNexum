package io.infranexum.itam.asset.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** ITAM asset command de-duplication store. */
public interface AssetIdempotencyRepository {
    record Record(String key, String payloadSha256, String operation, DomainIdentifier assetId, Instant createdAt) {
        public Record {
            Objects.requireNonNull(key, "key"); Objects.requireNonNull(payloadSha256, "payloadSha256");
            Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
    Optional<Record> find(String key);
    void insert(Record record);
}
