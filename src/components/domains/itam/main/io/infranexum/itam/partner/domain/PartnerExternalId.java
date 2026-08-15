package io.infranexum.itam.partner.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Official or integration identifier used for deterministic duplicate detection. */
public record PartnerExternalId(String authority, String value) {
    private static final Pattern AUTHORITY = Pattern.compile("[a-z][a-z0-9._-]{1,63}");
    public PartnerExternalId {
        Objects.requireNonNull(authority, "authority"); Objects.requireNonNull(value, "value");
        authority = authority.strip().toLowerCase(Locale.ROOT);
        value = value.strip();
        if (!AUTHORITY.matcher(authority).matches() || value.isEmpty() || value.length() > 240
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid external identifier");
        }
    }
    public String identityToken() { return authority + ":" + value.toUpperCase(Locale.ROOT); }
}
