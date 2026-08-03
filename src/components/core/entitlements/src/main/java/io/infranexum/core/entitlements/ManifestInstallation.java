package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;
import java.util.regex.Pattern;

/** Installation binding carried by every Pro or Enterprise activation manifest. */
public record ManifestInstallation(
        DomainIdentifier installationId,
        String fingerprintVersion,
        String fingerprint) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ManifestInstallation {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(fingerprintVersion, "fingerprintVersion");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprintVersion.matches("v[1-9][0-9]*")) {
            throw new IllegalArgumentException("fingerprintVersion must use vN format");
        }
        if (!SHA256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 value");
        }
    }

    public boolean matches(InstallationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return installationId.equals(identity.installationId())
                && fingerprintVersion.equals(identity.fingerprintVersion())
                && fingerprint.equals(identity.fingerprint());
    }
}
