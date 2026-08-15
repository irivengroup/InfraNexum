package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Ordered schema reference composing one immutable profile revision. */
public record SchemaProfileMember(int position, DomainIdentifier schemaId, boolean required) {
    public SchemaProfileMember {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1");
        Objects.requireNonNull(schemaId, "schemaId");
    }
}
