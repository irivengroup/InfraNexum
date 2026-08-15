package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Audit/correlation metadata required for registry mutations. */
public record SchemaRegistryCommandContext(DomainIdentifier actorId, DomainIdentifier correlationId) {
    public SchemaRegistryCommandContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
