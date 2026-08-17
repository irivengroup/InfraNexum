package io.infranexum.itam.asset.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Actor, correlation, idempotency and audit evidence carried by asset mutations. */
public record AssetCommandContext(
        DomainIdentifier actorId,
        DomainIdentifier correlationId,
        String idempotencyKey,
        String reason,
        String evidenceReference) {
    public AssetCommandContext {
        Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid idempotency key");
        idempotencyKey = idempotencyKey.strip();
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
        Objects.requireNonNull(reason, "reason");
        if (reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid asset mutation reason");
        reason = reason.strip();
        if (reason.length() < 2 || reason.length() > 1024) {
            throw new IllegalArgumentException("invalid asset mutation reason");
        }
        if (evidenceReference != null) {
            if (evidenceReference.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid evidenceReference");
            evidenceReference = evidenceReference.strip();
            if (evidenceReference.isEmpty()) evidenceReference = null;
            else if (evidenceReference.length() > 240) {
                throw new IllegalArgumentException("invalid evidenceReference");
            }
        }
    }
}
