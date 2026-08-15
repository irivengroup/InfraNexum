package io.infranexum.core.compatibility;

import java.time.Instant;

/** Inputs for a new immutable schema revision draft. */
public record CreateSchemaCommand(
        String schemaKey,
        SchemaKind kind,
        String owner,
        String version,
        String definitionJson,
        Instant effectiveAt) {}
