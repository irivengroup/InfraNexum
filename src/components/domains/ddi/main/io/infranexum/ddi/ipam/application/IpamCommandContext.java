package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Actor/correlation/audit metadata shared by IPAM mutations. */
public record IpamCommandContext(DomainIdentifier actorId,DomainIdentifier correlationId,String reason,String idempotencyKey){public IpamCommandContext{Objects.requireNonNull(actorId);Objects.requireNonNull(correlationId);reason=Objects.requireNonNull(reason,"reason");if(reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("reason must not contain control characters");reason=reason.strip();if(reason.length()<2||reason.length()>1024)throw new IllegalArgumentException("reason must contain 2..1024 characters");idempotencyKey=Objects.requireNonNull(idempotencyKey,"idempotencyKey");if(idempotencyKey.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("idempotencyKey must not contain control characters");idempotencyKey=idempotencyKey.strip();if(idempotencyKey.length()<8||idempotencyKey.length()>200||!idempotencyKey.matches("[A-Za-z0-9._:-]+"))throw new IllegalArgumentException("idempotencyKey must contain 8..200 safe characters");}}
