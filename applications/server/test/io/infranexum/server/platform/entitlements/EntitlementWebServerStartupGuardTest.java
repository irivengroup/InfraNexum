package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.core.entitlements.EntitlementAccessException;
import io.infranexum.core.entitlements.EntitlementErrorCodes;
import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;

class EntitlementWebServerStartupGuardTest {
    @Test
    void evaluatesEntitlementsAndRefreshesCapabilitiesBeforePortCreation() {
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        PlatformCapabilityProperties properties = mock(PlatformCapabilityProperties.class);
        PlatformCapabilityService capabilities = mock(PlatformCapabilityService.class);
        var status = ActivationTestFixtures.paidStatus();
        when(properties.profile()).thenReturn(status.profile());
        when(authority.initializeAndRequireStartup(status.profile())).thenReturn(status);

        new EntitlementWebServerStartupGuard(authority, properties, capabilities)
                .customize(mock(ConfigurableServletWebServerFactory.class));

        var order = inOrder(authority, capabilities);
        order.verify(authority).initializeAndRequireStartup(status.profile());
        order.verify(capabilities).applyEntitlementStatus(status);
    }
    @Test
    void propagatesHardStopBeforePublishingAnyCapabilitySnapshot() {
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        PlatformCapabilityProperties properties = mock(PlatformCapabilityProperties.class);
        PlatformCapabilityService capabilities = mock(PlatformCapabilityService.class);
        when(properties.profile()).thenReturn(io.infranexum.core.capabilities.InstallationProfile.LITE);
        when(authority.initializeAndRequireStartup(properties.profile())).thenThrow(
                new EntitlementAccessException(
                        EntitlementErrorCodes.LITE_HARD_STOPPED, "day 210 reached"));
        var guard = new EntitlementWebServerStartupGuard(authority, properties, capabilities);

        assertThrows(EntitlementAccessException.class, () -> guard.customize(
                mock(ConfigurableServletWebServerFactory.class)));
        verify(capabilities, never()).applyEntitlementStatus(org.mockito.ArgumentMatchers.any());
    }

}
