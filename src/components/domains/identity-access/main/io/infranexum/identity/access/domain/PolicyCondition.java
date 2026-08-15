package io.infranexum.identity.access.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One deterministic condition over a trusted PIP attribute. */
public record PolicyCondition(
        PolicyAttributeSource source,
        String attribute,
        PolicyOperator operator,
        String expectedValue) {
    private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public PolicyCondition {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(operator, "operator");
        attribute = normalizeAttribute(attribute);
        if (operator == PolicyOperator.EXISTS) {
            expectedValue = normalizeBoolean(expectedValue);
        } else {
            expectedValue = bounded(expectedValue, "expectedValue", 256);
        }
    }

    private static String normalizeAttribute(String value) {
        String normalized = bounded(value, "attribute", 64).toLowerCase(Locale.ROOT);
        if (!ATTRIBUTE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("policy attribute name is invalid");
        }
        return normalized;
    }

    private static String normalizeBoolean(String value) {
        String normalized = bounded(value, "expectedValue", 5).toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalArgumentException("EXISTS expectedValue must be true or false");
        }
        return normalized;
    }

    static String bounded(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (!value.equals(value.strip()) || value.isEmpty() || value.length() > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be trimmed and between 1 and " + maximum + " characters");
        }
        return value;
    }
}
