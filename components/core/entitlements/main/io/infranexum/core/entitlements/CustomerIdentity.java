package io.infranexum.core.entitlements;

import java.util.Objects;

/** Contractual customer identity embedded in an activation manifest. */
public record CustomerIdentity(String customerId, String legalName) {
    public CustomerIdentity {
        customerId = requireText(customerId, "customerId");
        legalName = requireText(legalName, "legalName");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty() || result.length() > 255) {
            throw new IllegalArgumentException(field + " must contain 1 to 255 characters");
        }
        return result;
    }
}
