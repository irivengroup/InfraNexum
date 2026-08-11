package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.core.entitlements.ActivationImportCoordinator;
import io.infranexum.core.entitlements.ActivationImportResult;
import io.infranexum.core.entitlements.ActivationManifestCodec;
import io.infranexum.core.entitlements.ActivationUsageState;
import io.infranexum.core.entitlements.ActivationVerificationResult;
import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import org.junit.jupiter.api.Test;

class ActivationAdministrationServiceTest {
    @Test
    void preflightsAndImportsThroughTheAuthoritativeRuntime() {
        ActivationManifestCodec codec = mock(ActivationManifestCodec.class);
        ActivationImportCoordinator coordinator = mock(ActivationImportCoordinator.class);
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        PlatformCapabilityProperties properties = mock(PlatformCapabilityProperties.class);
        PlatformCapabilityService capabilities = mock(PlatformCapabilityService.class);
        var manifest = ActivationTestFixtures.manifest();
        var verification = mock(ActivationVerificationResult.class);
        var imported = new ActivationImportResult(
                ActivationUsageState.ACTIVE, 1, ActivationTestFixtures.NOW.plusSeconds(31L * 86400L));
        var status = ActivationTestFixtures.paidStatus();
        when(codec.decode("document")).thenReturn(manifest);
        when(authority.preflight(manifest)).thenReturn(verification);
        when(coordinator.importManifest(manifest)).thenReturn(imported);
        when(properties.profile()).thenReturn(status.profile());
        when(authority.refresh(status.profile())).thenReturn(status);
        var service = new ActivationAdministrationService(
                codec, coordinator, authority, properties, capabilities);

        assertSame(verification, service.preflight("document"));
        assertSame(imported, service.importManifest("document"));
        verify(capabilities).applyEntitlementStatus(status);
    }
}
