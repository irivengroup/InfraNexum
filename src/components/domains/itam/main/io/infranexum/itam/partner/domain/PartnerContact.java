package io.infranexum.itam.partner.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Structured support/business contact; no free-form contact blob is persisted. */
public record PartnerContact(String type, String name, String email, String phone, String uri) {
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_-]{1,31}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    public PartnerContact {
        type = normalized(type, "type", 2, 32).toLowerCase(Locale.ROOT);
        if (!TYPE.matcher(type).matches()) throw new IllegalArgumentException("invalid contact type");
        name = normalized(name, "name", 2, 160);
        email = optional(email, "email", 320);
        if (email != null && !EMAIL.matcher(email).matches()) throw new IllegalArgumentException("invalid contact email");
        phone = optional(phone, "phone", 64);
        uri = optional(uri, "uri", 2048);
        if (uri != null) validateHttpUri(uri, "contact uri");
        if (email == null && phone == null && uri == null) {
            throw new IllegalArgumentException("contact requires email, phone or uri");
        }
    }

    private static String normalized(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field); String result = value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid contact " + field);
        }
        return result;
    }
    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        return normalized(value, field, 1, max);
    }
    private static void validateHttpUri(String value, String field) {
        URI parsed = URI.create(value);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || parsed.getHost() == null || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
