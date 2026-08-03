package io.infranexum.core.entitlements;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Computes a versioned fingerprint from installer-selected stable machine claims. */
public final class InstallationFingerprint {
    private InstallationFingerprint() {}

    public static String compute(String version, Map<String, String> claims) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(claims, "claims");
        if (!version.matches("v[1-9][0-9]*")) {
            throw new IllegalArgumentException("fingerprint version must use vN format");
        }
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("fingerprint claims must not be empty");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        claims.forEach((key, value) -> {
            String normalizedKey = requireText(key, "claim key").toLowerCase(java.util.Locale.ROOT);
            String normalizedValue = requireText(value, "claim value");
            if (normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                throw new IllegalArgumentException("duplicate normalized fingerprint claim: " + normalizedKey);
            }
        });
        StringBuilder canonical = new StringBuilder(version).append('\n');
        normalized.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", error);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty() || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank and single-line");
        }
        return result;
    }
}
