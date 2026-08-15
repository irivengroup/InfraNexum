package io.infranexum.server.rsot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.compatibility.CompatibilityVerdict;
import io.infranexum.core.compatibility.SchemaKind;
import org.junit.jupiter.api.Test;

/** Security and compatibility regressions for the declarative JSON Schema boundary. */
class JacksonSchemaDefinitionInspectorTest {
    private final JacksonSchemaDefinitionInspector inspector = new JacksonSchemaDefinitionInspector();

    @Test
    void acceptsBounded202012ObjectSchemasAndRoundTripsJsonTrees() {
        String json = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
        inspector.validate(SchemaKind.RSOT_CANONICAL, json);
        assertEquals(json, inspector.writeDefinition(inspector.parseDefinition(json)));
    }

    @Test
    void rejectsMalformedUnsupportedAndRemoteReferenceSchemas() {
        assertThrows(IllegalArgumentException.class, () -> inspector.validate(SchemaKind.API, "{"));
        assertThrows(IllegalArgumentException.class, () -> inspector.validate(SchemaKind.API, "[]"));
        assertThrows(IllegalArgumentException.class, () -> inspector.validate(SchemaKind.API, "{\"type\":\"string\"}"));
        assertThrows(IllegalArgumentException.class, () -> inspector.validate(
                SchemaKind.API,
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}"));
        assertThrows(IllegalArgumentException.class, () -> inspector.validate(
                SchemaKind.API,
                "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"https://example.invalid/schema.json\"}}}"));
        inspector.validate(SchemaKind.API, "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/x\"}},\"$defs\":{\"x\":{\"type\":\"string\"}}}");
    }

    @Test
    void extensionSchemasRejectExecutableAndIoKeywordsAtAnyDepth() {
        for (String key : new String[] {"script", "expression", "shell", "python", "javascript", "network", "url", "file", "process", "reflection"}) {
            String json = "{\"type\":\"object\",\"properties\":{\"safe\":{\"type\":\"object\",\"" + key + "\":\"forbidden\"}}}";
            assertThrows(IllegalArgumentException.class, () -> inspector.validate(SchemaKind.RSOT_EXTENSION, json), key);
            inspector.validate(SchemaKind.RSOT_CANONICAL, json);
        }
    }

    @Test
    void detectsBreakingRemovalTypeFormatEnumAndRequiredChanges() {
        String previous = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":[\"string\",\"null\"],\"enum\":[\"a\",\"b\"]},\"born\":{\"type\":\"string\",\"format\":\"date\"}}}";
        String candidate = "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\",\"enum\":[\"a\"]}}}";
        var report = inspector.compare(previous, candidate);
        assertEquals(CompatibilityVerdict.BREAKING, report.verdict());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("optional property became required")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("accepted JSON type set was narrowed")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("enum value removed")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("property removed: born")));
    }

    @Test
    void treatsUnprovenConstraintChangesAsIndeterminateAndOptionalAdditionsAsCompatible() {
        String previous = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
        String constrained = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"pattern\":\"^[A-Z]+$\"}}}";
        assertEquals(CompatibilityVerdict.INDETERMINATE, inspector.compare(previous, constrained).verdict());
        String additive = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"serial\":{\"type\":\"string\"}}}";
        assertEquals(CompatibilityVerdict.COMPATIBLE, inspector.compare(previous, additive).verdict());
    }
}
