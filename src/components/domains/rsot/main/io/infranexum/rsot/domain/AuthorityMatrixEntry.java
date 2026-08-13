package io.infranexum.rsot.domain;

import java.util.Objects;

/** One approved semantic row of the draft.21 initial RSOT authority matrix. */
public record AuthorityMatrixEntry(
        int position,
        String information,
        String authority,
        String rsotContribution,
        String conflictStrategy,
        String matrixVersion) {
    public AuthorityMatrixEntry {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1");
        information = text(information, "information");
        authority = text(authority, "authority");
        rsotContribution = text(rsotContribution, "rsotContribution");
        conflictStrategy = text(conflictStrategy, "conflictStrategy");
        matrixVersion = text(matrixVersion, "matrixVersion");
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 500 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
