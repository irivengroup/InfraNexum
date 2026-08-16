package io.infranexum.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.server.http.ApiProblemTestFixtures;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalAuthenticationFilterTest {
    private static final String CORRELATION_ID = "018bcfe5-6800-7001-8000-000000000001";
    @Test
    void bypassesNonApiAuthEndpointsAndPublicBuildMetadata() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var filter = filter(service);
        for (String path : new String[] {"/actuator/health/readiness", "/api/v1/iam/local-auth/session", "/api/v1/system/build"}) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reached.set(true));
            assertTrue(reached.get(), path);
        }
    }

    @Test
    void similarAuthPrefixDoesNotBypassAuthentication() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        when(service.validate(any())).thenThrow(new LocalSessionException("session is invalid"));
        var request = new MockHttpServletRequest("GET", "/api/v1/iam/local-authz");
        var response = new MockHttpServletResponse();
        filter(service).doFilter(request, response, (req, res) -> { throw new AssertionError(); });
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsMissingOrInvalidSessionWithoutInvokingApplication() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        when(service.validate(any())).thenThrow(new LocalSessionException("session is invalid"));
        var request = request("GET", "/api/v1/iam/organizations", null);
        CorrelationContext.bind(request, DomainIdentifier.parse(CORRELATION_ID));
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter(service).doFilter(request, response, (req, res) -> reached.set(true));
        assertFalse(reached.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INFRANEXUM_AUTHENTICATION_REQUIRED"));
        assertTrue(response.getContentAsString().contains("\"correlation_id\":\"" + CORRELATION_ID + "\""));
        assertTrue(response.getContentAsString().contains("\"trace_id\":\"" + CORRELATION_ID + "\""));
        assertEquals(CORRELATION_ID, response.getHeader(CorrelationContext.HEADER_NAME));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.isCommitted());
    }

    @Test
    void blocksBootstrapSessionUntilPasswordChange() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(LocalAuthTestFixtures.validated(true));
        var request = request("GET", "/api/v1/iam/organizations", LocalAuthTestFixtures.TOKEN);
        var response = new MockHttpServletResponse();
        filter(service).doFilter(request, response, (req, res) -> { throw new AssertionError(); });
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("INFRANEXUM_BOOTSTRAP_PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void safeMethodsRequireSessionButNotCsrf() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var validated = LocalAuthTestFixtures.validated(false);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(validated);
        var request = request("GET", "/api/v1/iam/organizations", LocalAuthTestFixtures.TOKEN);
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter(service).doFilter(request, response, (req, res) -> reached.set(true));
        assertTrue(reached.get());
        assertNotNull(request.getAttribute(LocalAuthenticationFilter.ACCOUNT_ATTRIBUTE));
    }

    @Test
    void mutationRequiresValidCsrfBeforeReachingApplication() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var validated = LocalAuthTestFixtures.validated(false);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(validated);
        doThrow(new LocalSessionException("CSRF validation failed")).when(service).verifyCsrf(validated, "wrong");
        var request = request("POST", "/api/v1/iam/organizations", LocalAuthTestFixtures.TOKEN);
        request.addHeader("X-CSRF-Token", "wrong");
        var response = new MockHttpServletResponse();
        filter(service).doFilter(request, response, (req, res) -> { throw new AssertionError(); });
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("INFRANEXUM_CSRF_VALIDATION_FAILED"));
    }

    @Test
    void validMutationBindsAccountAndContinues() throws Exception {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var validated = LocalAuthTestFixtures.validated(false);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(validated);
        var request = request("DELETE", "/api/v1/iam/organizations/018bcfe5-6800-7000-8000-000000000003", LocalAuthTestFixtures.TOKEN);
        request.addHeader("X-CSRF-Token", LocalAuthTestFixtures.CSRF);
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter(service).doFilter(request, response, (req, res) -> reached.set(true));
        assertTrue(reached.get());
        verify(service).verifyCsrf(validated, LocalAuthTestFixtures.CSRF);
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        var request = new MockHttpServletRequest(method, path);
        if (token != null) request.setCookies(new Cookie(LocalAuthController.SESSION_COOKIE, token));
        return request;
    }

    private static LocalAuthenticationFilter filter(LocalAuthenticationService service) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
        return new LocalAuthenticationFilter(service, ApiProblemTestFixtures.support(clock));
    }
}
