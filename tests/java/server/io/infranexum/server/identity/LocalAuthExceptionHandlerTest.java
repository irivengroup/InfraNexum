package io.infranexum.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.identity.local.domain.LocalAuthenticationException;
import io.infranexum.identity.local.domain.LocalPasswordPolicyException;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.server.http.ApiProblemTestFixtures;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LocalAuthExceptionHandlerTest {
    private final LocalAuthExceptionHandler handler = new LocalAuthExceptionHandler(
            ApiProblemTestFixtures.support(Clock.fixed(LocalAuthTestFixtures.NOW, ZoneOffset.UTC)));

    @Test
    void authenticationFailureIsGenericAndSecretFree() {
        var response = handler.authentication(request());
        assertEquals(401, response.getStatusCode().value());
        assertEquals("IAM_AUTHENTICATION_FAILED", response.getBody().code());
        assertFalse(response.getBody().toString().contains("admin"));
    }

    @Test
    void sessionAndCsrfFailuresHaveDistinctStableCodes() {
        var invalid = handler.session(new LocalSessionException("session is invalid"), request());
        assertEquals(401, invalid.getStatusCode().value());
        assertEquals("IAM_SESSION_INVALID", invalid.getBody().code());
        var csrf = handler.session(new LocalSessionException("CSRF validation failed"), request());
        assertEquals(403, csrf.getStatusCode().value());
        assertEquals("IAM_CSRF_REJECTED", csrf.getBody().code());
    }

    @Test
    void passwordPolicyFailureReturnsOnlyViolationIdentifiers() {
        var response = handler.password(new LocalPasswordPolicyException(List.of("uppercase", "special")), request());
        assertEquals(400, response.getStatusCode().value());
        assertEquals("IAM_LOCAL_PASSWORD_POLICY_VIOLATION", response.getBody().code());
        assertTrue(response.getBody().toString().contains("uppercase"));
        assertEquals(LocalAuthTestFixtures.NOW.toString(), response.getBody().timestamp());
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/iam/local-auth/session");
    }
}
