package io.infranexum.server.platform;

import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fail-closed runtime gate ensuring a published API operation is available in the effective installation. */
public final class ApiCapabilityFilter extends OncePerRequestFilter implements Ordered {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;
    public static final String CAPABILITY_ATTRIBUTE = "io.infranexum.api.capability";

    private final PlatformCapabilityService capabilities;
    private final ApiProblemSupport problems;

    public ApiCapabilityFilter(PlatformCapabilityService capabilities, ApiProblemSupport problems) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiCapabilityRequirement.resolve(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String capability = ApiCapabilityRequirement.resolve(request.getRequestURI());
        if (capability == null) {
            chain.doFilter(request, response);
            return;
        }
        if ("platform.bootstrap".equals(capability)) {
            request.setAttribute(CAPABILITY_ATTRIBUTE, capability);
            chain.doFilter(request, response);
            return;
        }
        try {
            if (!capabilities.explain(capability).available()) {
                reject(request, response, HttpStatus.NOT_FOUND, "INFRANEXUM_API_CAPABILITY_UNAVAILABLE",
                        "API operation is not available in the effective installation");
                return;
            }
        } catch (IllegalStateException unavailable) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "INFRANEXUM_CAPABILITY_RUNTIME_UNAVAILABLE",
                    "Capability runtime is not ready");
            return;
        }
        request.setAttribute(CAPABILITY_ATTRIBUTE, capability);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        problems.write(response, problems.problem(status, code, "API capability unavailable", detail,
                Map.of(), Map.of(), request));
    }
}
