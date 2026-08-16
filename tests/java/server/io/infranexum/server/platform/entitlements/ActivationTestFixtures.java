package io.infranexum.server.platform.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.ActivationManifest;
import io.infranexum.core.entitlements.ActivationManifestPayload;
import io.infranexum.core.entitlements.CustomerIdentity;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.core.entitlements.InstallationIdentity;
import io.infranexum.core.entitlements.ManifestInstallation;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

final class ActivationTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    static final DomainIdentifier INSTALLATION_ID =
            DomainIdentifier.parse("01989c82-7000-7abc-8def-0123456789ab");
    static final DomainIdentifier ACTIVATION_ID =
            DomainIdentifier.parse("01989c82-7001-7abc-8def-0123456789ab");

    private ActivationTestFixtures() {}

    static InstallationIdentity identity() {
        return new InstallationIdentity(INSTALLATION_ID, "v1", "a".repeat(64), NOW.minusSeconds(60));
    }

    static EntitlementRuntimeStatus liteStatus(EntitlementRuntimePhase phase, boolean startup, boolean mutation) {
        return new EntitlementRuntimeStatus(
                INSTALLATION_ID,
                InstallationProfile.LITE,
                AllocationTier.STANDARD,
                phase,
                NOW,
                NOW.minusSeconds(86400),
                NOW.plusSeconds(179L * 86400L),
                NOW.plusSeconds(209L * 86400L),
                null,
                null,
                0,
                null,
                Set.of(),
                Map.of(),
                startup,
                mutation);
    }

    static EntitlementRuntimeStatus paidStatus() {
        return new EntitlementRuntimeStatus(
                INSTALLATION_ID,
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                EntitlementRuntimePhase.ACTIVE,
                NOW,
                null,
                null,
                null,
                NOW.plusSeconds(86400),
                NOW.plusSeconds(31L * 86400L),
                1,
                ACTIVATION_ID,
                Set.of("iam.local-auth"),
                Map.of("iam.users.max", 10L),
                true,
                true);
    }

    static ActivationManifest manifest() {
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA,
                ACTIVATION_ID,
                new CustomerIdentity("customer-1", "InfraNexum Test"),
                new ManifestInstallation(INSTALLATION_ID, "v1", "a".repeat(64)),
                InstallationProfile.PRO,
                AllocationTier.STANDARD,
                "2.0.0-draft.21",
                10,
                Set.of("iam.local-auth"),
                Map.of("iam.users.max", 10L),
                NOW,
                NOW.plusSeconds(86400),
                30,
                NOW.minusSeconds(1),
                "test-issuer",
                1,
                "test-key");
        return new ActivationManifest(payload, Base64.getEncoder().encodeToString(new byte[64]));
    }
}
