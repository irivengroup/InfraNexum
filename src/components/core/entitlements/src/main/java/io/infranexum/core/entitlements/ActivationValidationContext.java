package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaCatalog;
import java.time.Instant;
import java.util.Objects;

/** Trusted local inputs used to bind and validate a detached activation manifest. */
public record ActivationValidationContext(
        InstallationIdentity installationIdentity,
        String customerId,
        InstallationProfile installedProfile,
        String catalogVersion,
        CapabilityCatalog capabilityCatalog,
        QuotaCatalog quotaCatalog,
        AcceptedSequence acceptedSequence,
        TrustedKeyStore trustedKeyStore,
        RevocationRegistry revocations,
        Instant now) {
    public ActivationValidationContext {
        Objects.requireNonNull(installationIdentity, "installationIdentity");
        customerId = requireText(customerId, "customerId");
        Objects.requireNonNull(installedProfile, "installedProfile");
        if (installedProfile == InstallationProfile.LITE) {
            throw new IllegalArgumentException("Lite cannot validate an activation without a profile migration");
        }
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        Objects.requireNonNull(capabilityCatalog, "capabilityCatalog");
        Objects.requireNonNull(quotaCatalog, "quotaCatalog");
        Objects.requireNonNull(acceptedSequence, "acceptedSequence");
        Objects.requireNonNull(trustedKeyStore, "trustedKeyStore");
        Objects.requireNonNull(revocations, "revocations");
        Objects.requireNonNull(now, "now");
        InstallationIdentity.requireWholeSecond(now, "now");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
