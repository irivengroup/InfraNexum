package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity created once by the installer and preserved across upgrades. */
public record InstallationIdentity(
        DomainIdentifier installationId,
        String fingerprintVersion,
        String fingerprint,
        Instant createdAt) {
    private static final Pattern VERSION = Pattern.compile("v[1-9][0-9]*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public InstallationIdentity {
        Objects.requireNonNull(installationId, "installationId");
        fingerprintVersion = requireText(fingerprintVersion, "fingerprintVersion");
        fingerprint = requireText(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!VERSION.matcher(fingerprintVersion).matches()) {
            throw new IllegalArgumentException("fingerprintVersion must use vN format");
        }
        if (!SHA256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 value");
        }
        requireWholeSecond(createdAt, "createdAt");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    static void requireWholeSecond(Instant value, String field) {
        if (value.getNano() != 0) {
            throw new IllegalArgumentException(field + " must be UTC at whole-second precision");
        }
    }
}
