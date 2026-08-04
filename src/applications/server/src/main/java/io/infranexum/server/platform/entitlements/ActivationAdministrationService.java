package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.ActivationImportCoordinator;
import io.infranexum.core.entitlements.ActivationImportResult;
import io.infranexum.core.entitlements.ActivationManifest;
import io.infranexum.core.entitlements.ActivationManifestCodec;
import io.infranexum.core.entitlements.ActivationVerificationResult;
import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.util.Objects;

/** Internal application service; HTTP import remains unavailable until IAM authorization is implemented. */
public final class ActivationAdministrationService {
    private final ActivationManifestCodec codec;
    private final ActivationImportCoordinator coordinator;
    private final EntitlementRuntimeAuthority authority;
    private final PlatformCapabilityProperties platformProperties;
    private final PlatformCapabilityService capabilityService;

    public ActivationAdministrationService(
            ActivationManifestCodec codec,
            ActivationImportCoordinator coordinator,
            EntitlementRuntimeAuthority authority,
            PlatformCapabilityProperties platformProperties,
            PlatformCapabilityService capabilityService) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.platformProperties = Objects.requireNonNull(platformProperties, "platformProperties");
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
    }

    public ActivationVerificationResult preflight(String document) {
        return authority.preflight(codec.decode(document));
    }

    public synchronized ActivationImportResult importManifest(String document) {
        ActivationManifest manifest = codec.decode(document);
        ActivationImportResult result = coordinator.importManifest(manifest);
        EntitlementRuntimeStatus status = authority.refresh(platformProperties.profile());
        capabilityService.applyEntitlementStatus(status);
        return result;
    }
}
