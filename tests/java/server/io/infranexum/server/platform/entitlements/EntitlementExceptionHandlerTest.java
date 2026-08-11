package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.infranexum.core.entitlements.EntitlementAccessException;
import io.infranexum.core.entitlements.EntitlementErrorCodes;
import io.infranexum.core.entitlements.EntitlementRuntimeUnavailableException;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

class EntitlementExceptionHandlerTest {
    private final EntitlementExceptionHandler handler = new EntitlementExceptionHandler(
            Clock.fixed(ActivationTestFixtures.NOW, ZoneOffset.UTC));

    @Test
    void translatesAccessDenialsToCanonicalProblemJson() {
        var request = new MockHttpServletRequest("POST", "/api/v1/objects");
        request.addHeader("X-Correlation-ID", " trace-123 ");
        var response = handler.handleAccess(new EntitlementAccessException(
                EntitlementErrorCodes.LITE_CONVERSION_REQUIRED, "read only"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("INFRANEXUM_LITE_CONVERSION_REQUIRED", response.getBody().code());
        assertEquals("/api/v1/objects", response.getBody().instance());
        assertEquals("trace-123", response.getBody().trace_id());
        assertEquals(ActivationTestFixtures.NOW, response.getBody().occurred_at());
    }

    @Test
    void translatesUnavailableRuntimeTo503WithoutInventingATrace() {
        var response = handler.handleUnavailable(
                new EntitlementRuntimeUnavailableException("not initialized"),
                new MockHttpServletRequest("GET", "/api/v1/platform/evaluation/status"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("INFRANEXUM_ENTITLEMENT_RUNTIME_UNAVAILABLE", response.getBody().code());
        assertNull(response.getBody().trace_id());
    }
}
