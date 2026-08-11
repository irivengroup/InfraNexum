package io.infranexum.core.capabilities;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Embedded certified quota catalogue and allocation-plan validator. */
public final class QuotaCatalog {
    public static final String RESOURCE = "/io/infranexum/core/capabilities/quota-catalog.csv";
    private static final Set<String> COLUMNS = Set.of(
            "component", "quota_key", "unit", "quota_class", "generator_adjustable", "lite_fixed",
            "pro_standard", "pro_advanced_ceiling", "enterprise_standard",
            "enterprise_ultimate_ceiling", "scope", "enforcement");

    private final String version;
    private final Map<String, QuotaDefinition> definitions;

    private QuotaCatalog(String version, Map<String, QuotaDefinition> definitions) {
        this.version = requireText(version, "version");
        this.definitions = Map.copyOf(definitions);
        if (this.definitions.isEmpty()) {
            throw new IllegalArgumentException("quota catalogue must not be empty");
        }
    }

    public static QuotaCatalog load(String version, Path path) {
        return fromRows(version, CsvTable.read(path));
    }

    public static QuotaCatalog loadEmbedded(String version) {
        return fromRows(version, CsvTable.read(new InputStreamReader(
                Objects.requireNonNull(QuotaCatalog.class.getResourceAsStream(RESOURCE), "missing " + RESOURCE),
                StandardCharsets.UTF_8)));
    }

    private static QuotaCatalog fromRows(String version, java.util.List<Map<String, String>> rows) {
        Map<String, QuotaDefinition> definitions = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            if (!row.keySet().equals(COLUMNS)) {
                throw new IllegalArgumentException("quota catalogue columns differ from contract: " + row.keySet());
            }
            QuotaDefinition definition = new QuotaDefinition(
                    row.get("component"),
                    row.get("quota_key"),
                    row.get("unit"),
                    QuotaClass.parse(row.get("quota_class")),
                    parseBoolean(row.get("generator_adjustable")),
                    parseLong(row, "lite_fixed"),
                    parseLong(row, "pro_standard"),
                    parseLong(row, "pro_advanced_ceiling"),
                    parseLong(row, "enterprise_standard"),
                    parseLong(row, "enterprise_ultimate_ceiling"),
                    row.get("scope"),
                    row.get("enforcement"));
            if (definitions.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalArgumentException("duplicate quota: " + definition.key());
            }
        }
        return new QuotaCatalog(version, definitions);
    }

    public String version() {
        return version;
    }

    public int size() {
        return definitions.size();
    }

    public Set<String> keys() {
        return definitions.keySet();
    }

    public QuotaDefinition require(String key) {
        QuotaDefinition definition = definitions.get(Objects.requireNonNull(key, "key"));
        if (definition == null) {
            throw new IllegalArgumentException("unknown quota: " + key);
        }
        return definition;
    }

    public QuotaAllocationPlan allocate(
            InstallationProfile profile,
            AllocationTier tier,
            String requestedCatalogVersion,
            Map<String, Long> overrides) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(overrides, "overrides");
        if (!version.equals(requestedCatalogVersion)) {
            throw new IllegalArgumentException("quota catalogue version mismatch");
        }
        validateTier(profile, tier);
        for (String key : overrides.keySet()) {
            require(key);
        }
        Map<String, Long> limits = new LinkedHashMap<>();
        for (QuotaDefinition definition : definitions.values()) {
            Long requested = overrides.get(definition.key());
            limits.put(definition.key(), resolve(definition, profile, tier, requested));
        }
        return new QuotaAllocationPlan(version, profile, tier, limits);
    }

    private static long resolve(
            QuotaDefinition definition,
            InstallationProfile profile,
            AllocationTier tier,
            Long requested) {
        if (definition.quotaClass() == QuotaClass.ARCHITECTURAL_FIXED) {
            if (requested != null) {
                throw new IllegalArgumentException("architectural quota cannot be overridden: " + definition.key());
            }
            return switch (profile) {
                case LITE -> definition.liteFixed();
                case PRO -> definition.proStandard();
                case ENTERPRISE -> definition.enterpriseStandard();
            };
        }
        return switch (profile) {
            case LITE -> {
                if (requested != null) {
                    throw new IllegalArgumentException("Lite quotas are fixed: " + definition.key());
                }
                yield definition.liteFixed();
            }
            case PRO -> resolvePro(definition, tier, requested);
            case ENTERPRISE -> resolveEnterprise(definition, tier, requested);
        };
    }

    private static long resolvePro(QuotaDefinition definition, AllocationTier tier, Long requested) {
        if (tier == AllocationTier.STANDARD) {
            long value = requested == null ? definition.proStandard() : requested;
            requireRange(definition.key(), value, 0, definition.proStandard());
            return value;
        }
        long value = requested == null ? definition.proStandard() : requested;
        requireRange(definition.key(), value, definition.proStandard(), definition.proAdvancedCeiling());
        // QuotaDefinition already certifies the Pro Advanced ceiling as strictly below
        // 50% of Enterprise Standard; requireRange keeps every override inside that ceiling.
        return value;
    }

    private static long resolveEnterprise(QuotaDefinition definition, AllocationTier tier, Long requested) {
        if (tier == AllocationTier.STANDARD) {
            long value = requested == null ? definition.enterpriseStandard() : requested;
            requireRange(definition.key(), value, 0, definition.enterpriseStandard());
            return value;
        }
        long value = requested == null ? definition.enterpriseStandard() : requested;
        requireRange(definition.key(), value, definition.enterpriseStandard(), definition.enterpriseUltimateCeiling());
        return value;
    }

    private static void validateTier(InstallationProfile profile, AllocationTier tier) {
        boolean valid = switch (profile) {
            case LITE -> tier == AllocationTier.STANDARD;
            case PRO -> tier == AllocationTier.STANDARD || tier == AllocationTier.ADVANCED;
            case ENTERPRISE -> tier == AllocationTier.STANDARD || tier == AllocationTier.ULTIMATE;
        };
        if (!valid) {
            throw new IllegalArgumentException("allocation tier is incompatible with installation profile");
        }
    }

    private static void requireRange(String key, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "quota " + key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static long parseLong(Map<String, String> row, String field) {
        try {
            return Long.parseLong(row.get(field));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid long for " + field + ": " + row.get(field), error);
        }
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

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
