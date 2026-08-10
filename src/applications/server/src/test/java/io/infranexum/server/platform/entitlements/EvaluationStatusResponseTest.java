package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import org.junit.jupiter.api.Test;

class EvaluationStatusResponseTest {
    @Test
    void mapsLiteStatusWithoutInventingActivationIdentifiers() {
        var response = EvaluationStatusResponse.from(
                ActivationTestFixtures.liteStatus(EntitlementRuntimePhase.EVALUATION, true, true));
        assertEquals("LITE", response.profile());
        assertEquals("EVALUATION", response.phase());
        assertNull(response.acceptedActivationId());
        assertEquals(0, response.acceptedSequence());
        assertTrue(response.serviceStartupPermitted());
    }

    @Test
    void mapsPaidActivationIdentityAndBoundaries() {
        var response = EvaluationStatusResponse.from(ActivationTestFixtures.paidStatus());
        assertEquals(ActivationTestFixtures.ACTIVATION_ID.toString(), response.acceptedActivationId());
        assertEquals(1, response.acceptedSequence());
        assertEquals("PRO", response.profile());
    }
}
