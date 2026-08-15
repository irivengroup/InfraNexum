package io.infranexum.itam.compliance.domain;

import java.util.Objects;

/** Shared validation primitives for contractual strings and proof references. */
final class ComplianceTexts {
    private ComplianceTexts() {}
    static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }
    static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        return text(value, field, 1, max);
    }
}
