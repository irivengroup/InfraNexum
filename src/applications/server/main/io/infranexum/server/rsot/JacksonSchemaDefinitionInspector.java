package io.infranexum.server.rsot;

import io.infranexum.core.compatibility.CompatibilityReport;
import io.infranexum.core.compatibility.CompatibilityVerdict;
import io.infranexum.core.compatibility.SchemaDefinitionInspector;
import io.infranexum.core.compatibility.SchemaKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 JSON-schema guard for PGM-06-E03.
 *
 * <p>The validator intentionally supports a conservative declarative subset. Any construct whose
 * compatibility cannot be proven is rejected at publication as INDETERMINATE instead of being
 * silently treated as backward compatible. Extensions additionally reject executable or I/O-shaped
 * keywords at every depth.</p>
 */
public final class JacksonSchemaDefinitionInspector implements SchemaDefinitionInspector {
    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> EXECUTABLE_KEYS = Set.of(
            "script", "scripts", "expression", "expressions", "eval", "exec", "command", "commands",
            "shell", "bash", "powershell", "python", "javascript", "js", "groovy", "network", "socket",
            "url", "uri", "file", "filesystem", "process", "runtime", "reflection", "classloader",
            "x-infranexum-rule", "x-infranexum-executable", "x-executable");
    private static final Set<String> NARROWING_KEYWORDS = Set.of(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength",
            "pattern", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties", "additionalProperties",
            "unevaluatedProperties", "contains", "minContains", "maxContains", "dependentRequired", "dependentSchemas",
            "propertyNames", "not", "allOf", "anyOf", "oneOf", "if", "then", "else", "const");

    private final JsonMapper mapper;

    public JacksonSchemaDefinitionInspector() {
        this(JsonMapper.builder().build());
    }

    JacksonSchemaDefinitionInspector(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void validate(SchemaKind kind, String definitionJson) {
        Objects.requireNonNull(kind, "kind");
        JsonNode root = parse(definitionJson);
        if (!root.isObject()) throw new IllegalArgumentException("schema definition root must be an object");
        JsonNode dialect = root.get("$schema");
        if (dialect != null && (!dialect.isTextual() || !JSON_SCHEMA_2020_12.equals(dialect.textValue()))) {
            throw new IllegalArgumentException("only JSON Schema 2020-12 is supported");
        }
        JsonNode type = root.get("type");
        if (type == null || !containsType(type, "object")) {
            throw new IllegalArgumentException("registry schemas must declare an object root type");
        }
        inspectNode(root, "$", kind == SchemaKind.RSOT_EXTENSION);
    }

    @Override
    public CompatibilityReport compare(String previousDefinitionJson, String candidateDefinitionJson) {
        JsonNode previous = parse(previousDefinitionJson);
        JsonNode candidate = parse(candidateDefinitionJson);
        List<String> breaking = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        compareObject(previous, candidate, "$", breaking, uncertain);
        if (!breaking.isEmpty()) return new CompatibilityReport(CompatibilityVerdict.BREAKING, breaking);
        if (!uncertain.isEmpty()) return new CompatibilityReport(CompatibilityVerdict.INDETERMINATE, uncertain);
        return CompatibilityReport.compatible();
    }

    /** Parses a registry definition for API responses after the domain string has been validated. */
    public JsonNode parseDefinition(String definitionJson) {
        return parse(definitionJson);
    }

    /** Serializes an API JSON tree without allowing non-JSON object representations into the domain. */
    public String writeDefinition(JsonNode definition) {
        Objects.requireNonNull(definition, "definition");
        if (!definition.isObject()) throw new IllegalArgumentException("definition must be a JSON object");
        try {
            return mapper.writeValueAsString(definition);
        } catch (Exception failure) {
            throw new IllegalArgumentException("definition cannot be serialized as JSON", failure);
        }
    }

    private JsonNode parse(String json) {
        Objects.requireNonNull(json, "definitionJson");
        try {
            return mapper.readTree(json);
        } catch (Exception failure) {
            throw new IllegalArgumentException("definitionJson must contain valid JSON", failure);
        }
    }

    private static void inspectNode(JsonNode node, String path, boolean extension) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("$ref".equals(key)) {
                    if (!value.isTextual() || value.textValue() == null || !value.textValue().startsWith("#")) {
                        throw new IllegalArgumentException("external $ref is forbidden at " + path);
                    }
                }
                if (extension && EXECUTABLE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("executable or I/O extension keyword is forbidden at " + path + "/" + key);
                }
                inspectNode(value, path + "/" + escapePointer(key), extension);
            });
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) inspectNode(item, path + "/" + index++, extension);
        }
    }

    private static void compareObject(
            JsonNode previous, JsonNode candidate, String path, List<String> breaking, List<String> uncertain) {
        compareType(previous, candidate, path, breaking);
        compareFormat(previous, candidate, path, breaking);
        compareEnum(previous, candidate, path, breaking);
        compareNarrowingKeywords(previous, candidate, path, uncertain);

        JsonNode previousRequired = previous.get("required");
        JsonNode candidateRequired = candidate.get("required");
        Set<String> oldRequired = textSet(previousRequired);
        Set<String> newRequired = textSet(candidateRequired);
        for (String field : newRequired) {
            if (!oldRequired.contains(field)) breaking.add(path + ": optional property became required: " + field);
        }

        JsonNode previousProperties = previous.get("properties");
        JsonNode candidateProperties = candidate.get("properties");
        if (previousProperties != null && previousProperties.isObject()) {
            if (candidateProperties == null || !candidateProperties.isObject()) {
                breaking.add(path + ": properties declaration was removed");
            } else {
                previousProperties.properties().forEach(entry -> {
                    JsonNode current = candidateProperties.get(entry.getKey());
                    if (current == null) {
                        breaking.add(path + ": property removed: " + entry.getKey());
                    } else if (entry.getValue().isObject() && current.isObject()) {
                        compareObject(entry.getValue(), current, path + "/properties/" + escapePointer(entry.getKey()), breaking, uncertain);
                    } else if (!entry.getValue().equals(current)) {
                        uncertain.add(path + ": non-object property schema changed: " + entry.getKey());
                    }
                });
            }
        }

        JsonNode previousItems = previous.get("items");
        JsonNode candidateItems = candidate.get("items");
        if (previousItems != null) {
            if (candidateItems == null) breaking.add(path + ": array item schema was removed");
            else if (previousItems.isObject() && candidateItems.isObject()) compareObject(previousItems, candidateItems, path + "/items", breaking, uncertain);
            else if (!previousItems.equals(candidateItems)) uncertain.add(path + ": array item schema changed in an unsupported form");
        }
    }

    private static void compareType(JsonNode previous, JsonNode candidate, String path, List<String> breaking) {
        Set<String> oldTypes = typeSet(previous.get("type"));
        Set<String> newTypes = typeSet(candidate.get("type"));
        if (!oldTypes.isEmpty() && (newTypes.isEmpty() || !newTypes.containsAll(oldTypes))) {
            breaking.add(path + ": accepted JSON type set was narrowed from " + oldTypes + " to " + newTypes);
        }
    }

    private static void compareFormat(JsonNode previous, JsonNode candidate, String path, List<String> breaking) {
        String oldFormat = text(previous.get("format"));
        String newFormat = text(candidate.get("format"));
        if (!Objects.equals(oldFormat, newFormat) && (oldFormat != null || newFormat != null)) {
            breaking.add(path + ": format changed from " + oldFormat + " to " + newFormat);
        }
    }

    private static void compareEnum(JsonNode previous, JsonNode candidate, String path, List<String> breaking) {
        JsonNode oldEnum = previous.get("enum");
        if (oldEnum == null || !oldEnum.isArray()) return;
        JsonNode newEnum = candidate.get("enum");
        if (newEnum == null || !newEnum.isArray()) return;
        Set<JsonNode> newValues = new HashSet<>();
        for (JsonNode value : newEnum) newValues.add(value);
        for (JsonNode value : oldEnum) if (!newValues.contains(value)) breaking.add(path + ": enum value removed: " + value);
    }

    private static void compareNarrowingKeywords(JsonNode previous, JsonNode candidate, String path, List<String> uncertain) {
        for (String keyword : NARROWING_KEYWORDS) {
            JsonNode oldValue = previous.get(keyword);
            JsonNode newValue = candidate.get(keyword);
            if (!Objects.equals(oldValue, newValue) && (oldValue != null || newValue != null)) {
                uncertain.add(path + ": compatibility of keyword '" + keyword + "' requires explicit schema review");
            }
        }
    }

    private static boolean containsType(JsonNode node, String expected) {
        return typeSet(node).contains(expected);
    }

    private static Set<String> typeSet(JsonNode node) {
        if (node == null) return Set.of();
        if (node.isTextual()) return node.textValue() == null ? Set.of() : Set.of(node.textValue());
        if (!node.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : node) if (value.isTextual() && value.textValue() != null) result.add(value.textValue());
        return Set.copyOf(result);
    }

    private static Set<String> textSet(JsonNode node) {
        if (node == null || !node.isArray()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) if (item.isTextual() && item.textValue() != null) values.add(item.textValue());
        return Set.copyOf(values);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
