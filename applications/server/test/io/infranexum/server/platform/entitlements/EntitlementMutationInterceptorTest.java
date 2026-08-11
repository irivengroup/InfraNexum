package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EntitlementMutationInterceptorTest {
    @Test
    void guardsOnlyMutatingApiMethods() {
        EntitlementRuntimeAuthority authority = org.mockito.Mockito.mock(EntitlementRuntimeAuthority.class);
        var interceptor = new EntitlementMutationInterceptor(authority);
        var response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request("GET", "/api/v1/platform/evaluation/status"), response, this));
        verify(authority, never()).requireMutation();

        for (String method : java.util.List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertTrue(interceptor.preHandle(request(method, "/api/v1/objects"), response, this));
        }
        verify(authority, org.mockito.Mockito.times(4)).requireMutation();

        assertTrue(interceptor.preHandle(request("POST", "/actuator/health"), response, this));
        verify(authority, org.mockito.Mockito.times(4)).requireMutation();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
