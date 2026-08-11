package io.infranexum.core.capabilities;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable machine-readable capability catalogue. */
public final class CapabilityCatalog {
    public static final String RESOURCE = "/io/infranexum/core/capabilities/capability-catalog.csv";
    private final String version;
    private final Map<CapabilityCode, CapabilityDefinition> definitions;

    private CapabilityCatalog(String version, Map<CapabilityCode, CapabilityDefinition> definitions) {
        this.version = requireText(version, "version");
        this.definitions = Map.copyOf(definitions);
        if (this.definitions.isEmpty()) {
            throw new IllegalArgumentException("capability catalogue must not be empty");
        }
    }

    public static CapabilityCatalog load(String version, Path path) {
        return fromRows(version, CsvTable.read(path));
    }

    public static CapabilityCatalog loadEmbedded(String version) {
        Reader reader = new InputStreamReader(
                Objects.requireNonNull(CapabilityCatalog.class.getResourceAsStream(RESOURCE), "missing " + RESOURCE),
                StandardCharsets.UTF_8);
        return fromRows(version, CsvTable.read(reader));
    }

    private static CapabilityCatalog fromRows(String version, java.util.List<Map<String, String>> rows) {
        Map<CapabilityCode, CapabilityDefinition> definitions = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            requireColumns(row, Set.of(
                    "capability_code", "allowed_profiles", "required_roles", "allowed_topologies",
                    "required_traits", "activation_protected"));
            CapabilityDefinition definition = new CapabilityDefinition(
                    new CapabilityCode(row.get("capability_code")),
                    parseSet(row.get("allowed_profiles"), InstallationProfile::parse, InstallationProfile.class),
                    parseSet(row.get("required_roles"), DeploymentRole::parse, DeploymentRole.class),
                    parseSet(row.get("allowed_topologies"), InstallationTopology::parse, InstallationTopology.class),
                    parseSet(row.get("required_traits"), TechnicalTrait::parse, TechnicalTrait.class),
                    parseBoolean(row.get("activation_protected")));
            if (definitions.putIfAbsent(definition.code(), definition) != null) {
                throw new IllegalArgumentException("duplicate capability: " + definition.code());
            }
        }
        return new CapabilityCatalog(version, definitions);
    }

    public String version() {
        return version;
    }

    public Set<CapabilityCode> codes() {
        return definitions.keySet();
    }

    public CapabilityDefinition find(CapabilityCode code) {
        return definitions.get(Objects.requireNonNull(code, "code"));
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid boolean: " + value);
    }

    private static <E extends Enum<E>> Set<E> parseSet(
            String value, Function<String, E> parser, Class<E> enumType) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return value.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(";")))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .map(parser)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireColumns(Map<String, String> row, Set<String> expected) {
        if (!row.keySet().equals(expected)) {
            throw new IllegalArgumentException("capability catalogue columns differ from contract: " + row.keySet());
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
