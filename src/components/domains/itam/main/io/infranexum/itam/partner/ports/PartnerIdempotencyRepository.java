package io.infranexum.itam.partner.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** ITAM-owned idempotency store; keys never cross bounded contexts. */
public interface PartnerIdempotencyRepository {
    record Record(String key, String payloadSha256, String operation, DomainIdentifier partnerId, Instant createdAt) {
        public Record {
            Objects.requireNonNull(key, "key"); Objects.requireNonNull(payloadSha256, "payloadSha256");
            Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(partnerId, "partnerId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
    Optional<Record> find(String key);
    void insert(Record record);
}
