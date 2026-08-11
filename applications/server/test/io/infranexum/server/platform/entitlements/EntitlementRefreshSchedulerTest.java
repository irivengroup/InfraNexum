package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class EntitlementRefreshSchedulerTest {
    @Test
    void refreshesCapabilitiesAndClosesTheContextOnHardStopOrIntegrityFailure() {
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        PlatformCapabilityProperties properties = mock(PlatformCapabilityProperties.class);
        PlatformCapabilityService capabilities = mock(PlatformCapabilityService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(properties.profile()).thenReturn(io.infranexum.core.capabilities.InstallationProfile.LITE);
        var scheduler = new EntitlementRefreshScheduler(authority, properties, capabilities, context);

        var active = ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.EVALUATION, true, true);
        when(authority.refresh(properties.profile())).thenReturn(active);
        scheduler.refresh();
        verify(capabilities).applyEntitlementStatus(active);
        verify(context, never()).close();

        var stopped = ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.HARD_STOPPED, false, false);
        when(authority.refresh(properties.profile())).thenReturn(stopped);
        scheduler.refresh();
        verify(context).close();

        when(authority.refresh(properties.profile())).thenThrow(new IllegalStateException("proof mismatch"));
        assertThrows(IllegalStateException.class, scheduler::refresh);
        verify(context, org.mockito.Mockito.times(2)).close();
    }
}
