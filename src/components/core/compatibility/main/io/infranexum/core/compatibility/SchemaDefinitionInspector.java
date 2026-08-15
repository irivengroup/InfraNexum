package io.infranexum.core.compatibility;

/**
 * Security boundary for JSON Schema syntax, declarative-only validation and compatibility analysis.
 *
 * <p>Implementations must never resolve remote references or execute user-provided expressions.
 */
public interface SchemaDefinitionInspector {
    void validate(SchemaKind kind, String definitionJson);

    CompatibilityReport compare(String previousDefinitionJson, String candidateDefinitionJson);
}
