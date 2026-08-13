package io.infranexum.server.identity;

import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.identity.local.domain.LocalPasswordPolicy;
import io.infranexum.identity.local.domain.LocalPasswordPolicyException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/iam/local-auth")
@ConditionalOnProperty(name = "infranexum.identity.local.enabled", havingValue = "true")
public class LocalAuthController {
    static final String SESSION_COOKIE = "INX_SESSION";
    static final String CSRF_COOKIE = "INX_XSRF";
    private final LocalAuthenticationService service;
    private final LocalAuthRuntimeProperties properties;
    private final LocalPasswordPolicy passwordPolicy = new LocalPasswordPolicy();

    public LocalAuthController(LocalAuthenticationService service, LocalAuthRuntimeProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/session")
    ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        char[] password = request.password().toCharArray();
        AuthenticatedSession authenticated = service.authenticate(request.username(), password);
        issueCookies(response, authenticated);
        return noStore(SessionResponse.from(authenticated));
    }

    @GetMapping("/session")
    ResponseEntity<SessionResponse> current(HttpServletRequest request) {
        ValidatedSession validated = service.validate(cookie(request, SESSION_COOKIE));
        return noStore(SessionResponse.from(validated));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf) {
        ValidatedSession validated = service.validate(cookie(request, SESSION_COOKIE));
        service.verifyCsrf(validated, csrf);
        service.logout(validated);
        clearCookies(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/password")
    ResponseEntity<SessionResponse> changePassword(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @Valid @RequestBody ChangePasswordRequest body) {
        ValidatedSession validated = service.validate(cookie(request, SESSION_COOKIE));
        service.verifyCsrf(validated, csrf);
        AuthenticatedSession replacement = service.changePassword(
                validated, body.currentPassword().toCharArray(), body.newPassword().toCharArray());
        issueCookies(response, replacement);
        return noStore(SessionResponse.from(replacement));
    }

    @PostMapping("/password-policy/validate")
    ResponseEntity<Map<String, Object>> validatePassword(@Valid @RequestBody PasswordValidationRequest body) {
        char[] password = body.password().toCharArray();
        try {
            passwordPolicy.validate(password);
            return noStore(Map.of("valid", true, "violations", List.of()));
        } catch (LocalPasswordPolicyException failure) {
            return noStore(Map.of("valid", false, "violations", failure.violations()));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void issueCookies(HttpServletResponse response, AuthenticatedSession authenticated) {
        Duration ttl = Duration.between(authenticated.session().createdAt(), authenticated.session().absoluteExpiresAt());
        response.addHeader("Set-Cookie", cookieHeader(SESSION_COOKIE, authenticated.bearerToken(), true, ttl));
        response.addHeader("Set-Cookie", cookieHeader(CSRF_COOKIE, authenticated.csrfToken(), false, ttl));
    }

    private void clearCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", expiredCookie(SESSION_COOKIE, true));
        response.addHeader("Set-Cookie", expiredCookie(CSRF_COOKIE, false));
    }

    private String cookieHeader(String name, String value, boolean httpOnly, Duration ttl) {
        StringBuilder result = new StringBuilder(name).append('=').append(value)
                .append("; Path=/; SameSite=Strict; Max-Age=").append(Math.max(1, ttl.toSeconds()));
        if (httpOnly) result.append("; HttpOnly");
        if (properties.cookieSecure()) result.append("; Secure");
        return result.toString();
    }

    private String expiredCookie(String name, boolean httpOnly) {
        StringBuilder result = new StringBuilder(name).append("=; Path=/; SameSite=Strict; Max-Age=0");
        if (httpOnly) result.append("; HttpOnly");
        if (properties.cookieSecure()) result.append("; Secure");
        return result.toString();
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record LoginRequest(@NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 128) String password) {}
    record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword, @NotBlank @Size(max = 128) String newPassword) {}
    record PasswordValidationRequest(@NotBlank @Size(max = 128) String password) {}

    record SessionResponse(
            String sessionId,
            String accountId,
            String username,
            String displayName,
            boolean mustChange,
            String idleExpiresAt,
            String absoluteExpiresAt) {
        static SessionResponse from(AuthenticatedSession authenticated) {
            return from(new ValidatedSession(authenticated.account(), authenticated.session()));
        }
        static SessionResponse from(ValidatedSession validated) {
            return new SessionResponse(
                    validated.session().id().toString(), validated.account().id().toString(),
                    validated.account().username(), validated.account().displayName(), validated.account().mustChange(),
                    validated.session().idleExpiresAt().toString(), validated.session().absoluteExpiresAt().toString());
        }
    }
}
