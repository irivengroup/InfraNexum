package io.infranexum.server.identity;

import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local-authentication boundary that establishes the actor consumed by the downstream RBAC PEP.
 *
 * <p>The filter authenticates every v1 API except the local-authentication endpoints themselves.
 * Bootstrap credentials are intentionally restricted to password replacement until the mandatory
 * change has completed. Browser mutations additionally require the double-submit CSRF token.
 */
public final class LocalAuthenticationFilter extends OncePerRequestFilter implements Ordered {
    public static final String ACCOUNT_ATTRIBUTE = LocalAuthenticationFilter.class.getName() + ".account";
    private static final String API_PREFIX = "/api/v1/";
    private static final String AUTH_PREFIX = "/api/v1/iam/local-auth";
    private static final String PUBLIC_BUILD_PATH = "/api/v1/system/build";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final LocalAuthenticationService service;
    private final ApiProblemSupport problems;

    public LocalAuthenticationFilter(LocalAuthenticationService service, ApiProblemSupport problems) {
        this.service = Objects.requireNonNull(service, "service");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX) || path.equals(AUTH_PREFIX) || path.startsWith(AUTH_PREFIX + "/") || PUBLIC_BUILD_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        ValidatedSession validated;
        try {
            validated = service.validate(cookie(request, LocalAuthController.SESSION_COOKIE));
        } catch (LocalSessionException invalid) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INFRANEXUM_AUTHENTICATION_REQUIRED", "Authentication required");
            return;
        }

        if (validated.account().mustChange()) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "INFRANEXUM_BOOTSTRAP_PASSWORD_CHANGE_REQUIRED", "Bootstrap password change required");
            return;
        }

        if (!SAFE_METHODS.contains(request.getMethod())) {
            try {
                service.verifyCsrf(validated, request.getHeader("X-CSRF-Token"));
            } catch (LocalSessionException invalidCsrf) {
                reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                        "INFRANEXUM_CSRF_VALIDATION_FAILED", "CSRF validation failed");
                return;
            }
        }

        request.setAttribute(ACCOUNT_ATTRIBUTE, validated.account().id());
        chain.doFilter(request, response);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private void reject(
            HttpServletRequest request, HttpServletResponse response, int status, String code, String title)
            throws IOException {
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        problems.write(response, problems.problem(
                httpStatus,
                code,
                title,
                title,
                java.util.Map.of(),
                java.util.Map.of(),
                request));
    }
}
