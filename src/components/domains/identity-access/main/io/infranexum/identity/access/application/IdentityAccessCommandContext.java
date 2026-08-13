package io.infranexum.identity.access.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Actor/correlation metadata required by every IAM RBAC mutation. */
public record IdentityAccessCommandContext(DomainIdentifier actorId, DomainIdentifier correlationId, String reason, String origin) {
    public IdentityAccessCommandContext {
        Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(correlationId, "correlationId");
        reason = text(reason, "reason", 1024); origin = text(origin, "origin", 128);
    }
    private static String text(String value, String field, int max) {
        Objects.requireNonNull(value, field); String normalized=value.strip();
        if (normalized.isEmpty() || normalized.length()>max || normalized.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid "+field);
        return normalized;
    }
}
