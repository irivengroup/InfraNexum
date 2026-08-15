package io.infranexum.ddi.ipam.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Optional;

/** Durable deduplication for mutating IPAM commands. */
public interface IpamIdempotencyRepository { record Record(String key,String payloadSha256,String operation,DomainIdentifier resultId,Instant createdAt){} Optional<Record> find(String key); void insert(Record record); }
