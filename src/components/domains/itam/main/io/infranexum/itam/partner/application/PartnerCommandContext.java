package io.infranexum.itam.partner.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Actor, correlation, idempotency and audit reason carried by Partner mutations. */
public record PartnerCommandContext(
        DomainIdentifier actorId, DomainIdentifier correlationId, String idempotencyKey, String reason) {
    public PartnerCommandContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        idempotencyKey = idempotencyKey.strip();
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 200
                || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
        Objects.requireNonNull(reason, "reason");
        reason = reason.strip();
        if (reason.length() < 2 || reason.length() > 1024 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid partner mutation reason");
        }
    }
}
