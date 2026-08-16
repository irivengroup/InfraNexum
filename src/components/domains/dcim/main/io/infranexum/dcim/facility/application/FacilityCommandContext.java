package io.infranexum.dcim.facility.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Actor, correlation and idempotency context carried by every DCIM mutation. */
public record FacilityCommandContext(DomainIdentifier actorId, DomainIdentifier correlationId, String idempotencyKey, String reason) {
    public FacilityCommandContext {
        Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey"); Objects.requireNonNull(reason, "reason");
        if (idempotencyKey.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid idempotencyKey");
        if (reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid reason");
        idempotencyKey=idempotencyKey.strip(); reason=reason.strip();
        if (idempotencyKey.length()<8 || idempotencyKey.length()>200) throw new IllegalArgumentException("invalid idempotencyKey");
        if (reason.length()<2 || reason.length()>1024) throw new IllegalArgumentException("invalid reason");
    }
}
