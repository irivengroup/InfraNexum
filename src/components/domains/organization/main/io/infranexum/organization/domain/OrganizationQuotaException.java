package io.infranexum.organization.domain;

import java.util.Objects;

/** Quota denial raised before an augmentative organization mutation. */
public final class OrganizationQuotaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String quotaKey;

    public OrganizationQuotaException(String quotaKey) {
        super("organization quota denies this allocation");
        this.quotaKey = Objects.requireNonNull(quotaKey, "quotaKey");
        if (quotaKey.isBlank()) {
            throw new IllegalArgumentException("quotaKey must not be blank");
        }
    }

    public String quotaKey() {
        return quotaKey;
    }
}
