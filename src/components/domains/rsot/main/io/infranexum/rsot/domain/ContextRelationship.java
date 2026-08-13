package io.infranexum.rsot.domain;

import java.util.Objects;

/** Approved RSOT context-map relationship; direct storage writes are always forbidden. */
public record ContextRelationship(int position, String provider, String contribution, boolean directStorageWriteAllowed) {
    public ContextRelationship {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1");
        provider = text(provider, "provider");
        contribution = text(contribution, "contribution");
        if (directStorageWriteAllowed) {
            throw new IllegalArgumentException("RSOT context map forbids direct writes to another context storage");
        }
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
