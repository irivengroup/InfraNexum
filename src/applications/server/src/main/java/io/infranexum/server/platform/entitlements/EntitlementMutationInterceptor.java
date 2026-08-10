package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies the entitlement mutation guard uniformly to every mutating API request. */
public final class EntitlementMutationInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final EntitlementRuntimeAuthority authority;

    public EntitlementMutationInterceptor(EntitlementRuntimeAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Objects.requireNonNull(request, "request");
        if (request.getRequestURI().startsWith("/api/")
                && MUTATING_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            authority.requireMutation();
        }
        return true;
    }
}
