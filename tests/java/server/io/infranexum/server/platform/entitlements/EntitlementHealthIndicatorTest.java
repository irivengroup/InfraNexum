package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import org.junit.jupiter.api.Test;

class EntitlementHealthIndicatorTest {
    @Test
    void reportsUpForRunnableStatesAndDownForUnavailableRuntime() {
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        when(authority.currentStatus()).thenReturn(
                ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.CONVERSION_REQUIRED, true, false));
        assertEquals("UP", new EntitlementHealthIndicator(authority).health().getStatus().getCode());

        when(authority.currentStatus()).thenReturn(
                ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.HARD_STOPPED, false, false));
        assertEquals("DOWN", new EntitlementHealthIndicator(authority).health().getStatus().getCode());

        when(authority.currentStatus()).thenThrow(new IllegalStateException("not initialized"));
        assertEquals("DOWN", new EntitlementHealthIndicator(authority).health().getStatus().getCode());
    }
}
