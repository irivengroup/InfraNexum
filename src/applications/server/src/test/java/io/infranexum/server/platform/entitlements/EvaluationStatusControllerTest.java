package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import org.junit.jupiter.api.Test;

class EvaluationStatusControllerTest {
    @Test
    void returnsNoStoreAuthoritativeStatus() {
        EntitlementRuntimeAuthority authority = mock(EntitlementRuntimeAuthority.class);
        when(authority.currentStatus()).thenReturn(
                ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.CONVERSION_REQUIRED, true, false));
        var response = new EvaluationStatusController(authority).status();
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("CONVERSION_REQUIRED", response.getBody().phase());
        assertEquals(false, response.getBody().mutationPermitted());
    }
}
