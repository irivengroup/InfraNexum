package io.infranexum.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.infranexum.identity.local.application.LocalAuthenticationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalAuthControllerTest {
    @Test
    void loginCreatesNoStoreStrictCookiesWithoutLeakingHashes() {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        when(service.authenticate(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.any(char[].class)))
                .thenReturn(LocalAuthTestFixtures.authenticated(true));
        var response = new MockHttpServletResponse();
        var result = new LocalAuthController(service, LocalAuthTestFixtures.properties(true))
                .login(new LocalAuthController.LoginRequest("admin", "BootstrapSecret!Aa1"), response);
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertTrue(result.getBody().mustChange());
        var cookies = response.getHeaders("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.get(0).contains("INX_SESSION=" + LocalAuthTestFixtures.TOKEN));
        assertTrue(cookies.get(0).contains("HttpOnly"));
        assertTrue(cookies.get(0).contains("SameSite=Strict"));
        assertTrue(cookies.get(0).contains("Secure"));
        assertTrue(cookies.get(1).contains("INX_XSRF=" + LocalAuthTestFixtures.CSRF));
        assertFalse(cookies.get(1).contains("HttpOnly"));
    }

    @Test
    void currentUsesOpaqueCookieAndReturnsSessionProjection() {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(LocalAuthTestFixtures.validated(false));
        var request = requestWithSession();
        var result = new LocalAuthController(service, LocalAuthTestFixtures.properties(false)).current(request);
        assertEquals("admin", result.getBody().username());
        assertEquals("Local Administrator", result.getBody().displayName());
        assertFalse(result.getBody().mustChange());
        assertEquals("no-store", result.getHeaders().getCacheControl());
    }

    @Test
    void logoutValidatesCsrfRevokesServerSessionAndExpiresCookies() {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var validated = LocalAuthTestFixtures.validated(false);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(validated);
        var response = new MockHttpServletResponse();
        var result = new LocalAuthController(service, LocalAuthTestFixtures.properties(false))
                .logout(requestWithSession(), response, LocalAuthTestFixtures.CSRF);
        verify(service).verifyCsrf(validated, LocalAuthTestFixtures.CSRF);
        verify(service).logout(validated);
        assertEquals(204, result.getStatusCode().value());
        assertTrue(response.getHeaders("Set-Cookie").stream().allMatch(value -> value.contains("Max-Age=0")));
    }

    @Test
    void passwordChangeRotatesSessionCookiesAndPassesMutableSecretsOnlyToService() {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var validated = LocalAuthTestFixtures.validated(true);
        when(service.validate(LocalAuthTestFixtures.TOKEN)).thenReturn(validated);
        when(service.changePassword(org.mockito.ArgumentMatchers.eq(validated), org.mockito.ArgumentMatchers.any(char[].class), org.mockito.ArgumentMatchers.any(char[].class)))
                .thenReturn(LocalAuthTestFixtures.authenticated(false));
        var response = new MockHttpServletResponse();
        var result = new LocalAuthController(service, LocalAuthTestFixtures.properties(false)).changePassword(
                requestWithSession(), response, LocalAuthTestFixtures.CSRF,
                new LocalAuthController.ChangePasswordRequest("BootstrapSecret!Aa1", "ReplacementSecret!Aa2"));
        assertFalse(result.getBody().mustChange());
        assertEquals(2, response.getHeaders("Set-Cookie").size());
        verify(service).verifyCsrf(validated, LocalAuthTestFixtures.CSRF);
    }

    @Test
    void passwordPolicyEndpointReturnsDeterministicViolationsWithoutEchoingPassword() {
        LocalAuthenticationService service = mock(LocalAuthenticationService.class);
        var controller = new LocalAuthController(service, LocalAuthTestFixtures.properties(false));
        var valid = controller.validatePassword(new LocalAuthController.PasswordValidationRequest("ValidPassword!1"));
        assertEquals(true, valid.getBody().get("valid"));
        var invalid = controller.validatePassword(new LocalAuthController.PasswordValidationRequest("weak"));
        assertEquals(false, invalid.getBody().get("valid"));
        assertFalse(invalid.getBody().toString().contains("weak"));
    }

    private static MockHttpServletRequest requestWithSession() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(LocalAuthController.SESSION_COOKIE, LocalAuthTestFixtures.TOKEN));
        return request;
    }
}
