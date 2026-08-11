package io.infranexum.core.capabilities;

import java.util.Objects;
import java.util.regex.Pattern;

/** One certified quota definition from the embedded draft.21 catalogue. */
public record QuotaDefinition(
        String component,
        String key,
        String unit,
        QuotaClass quotaClass,
        boolean generatorAdjustable,
        long liteFixed,
        long proStandard,
        long proAdvancedCeiling,
        long enterpriseStandard,
        long enterpriseUltimateCeiling,
        String scope,
        String enforcement) {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){1,}");

    public QuotaDefinition {
        component = requireText(component, "component");
        key = requireText(key, "key");
        unit = requireText(unit, "unit");
        Objects.requireNonNull(quotaClass, "quotaClass");
        scope = requireText(scope, "scope");
        enforcement = requireText(enforcement, "enforcement");
        if (!KEY.matcher(key).matches() || !key.startsWith(component + ".")) {
            throw new IllegalArgumentException("invalid quota key: " + key);
        }
        if (liteFixed < 0 || proStandard < 0 || proAdvancedCeiling < 0
                || enterpriseStandard < 0 || enterpriseUltimateCeiling < 0) {
            throw new IllegalArgumentException("quota values must be non-negative");
        }
        if (proAdvancedCeiling < proStandard || enterpriseUltimateCeiling < enterpriseStandard) {
            throw new IllegalArgumentException("quota ceilings must not be below standard values");
        }
        if (quotaClass == QuotaClass.ARCHITECTURAL_FIXED && generatorAdjustable) {
            throw new IllegalArgumentException("architectural quotas cannot be generator-adjustable");
        }
        if (quotaClass == QuotaClass.COMMERCIAL_SCALABLE && !generatorAdjustable) {
            throw new IllegalArgumentException("commercial quotas must be generator-adjustable");
        }
        if (quotaClass == QuotaClass.COMMERCIAL_SCALABLE
                && Math.multiplyExact(proAdvancedCeiling, 2L) >= enterpriseStandard) {
            throw new IllegalArgumentException("Pro Advanced ceiling must be strictly below 50% of Enterprise Standard");
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
