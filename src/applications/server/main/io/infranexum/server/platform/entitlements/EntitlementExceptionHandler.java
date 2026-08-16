package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementAccessException;
import io.infranexum.core.entitlements.EntitlementRuntimeUnavailableException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates entitlement failures into the canonical non-sensitive RFC 9457 problem contract. */
@ConditionalOnProperty(name = "infranexum.entitlements.enabled", havingValue = "true", matchIfMissing = true)
@RestControllerAdvice
public final class EntitlementExceptionHandler {
    private final ApiProblemSupport problems;

    public EntitlementExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(EntitlementAccessException.class)
    public ResponseEntity<ApiProblem> handleAccess(EntitlementAccessException error, HttpServletRequest request) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Opération interdite par les droits d’usage",
                error.getMessage(),
                error.code().value(),
                request);
    }

    @ExceptionHandler(EntitlementRuntimeUnavailableException.class)
    public ResponseEntity<ApiProblem> handleUnavailable(
            EntitlementRuntimeUnavailableException error, HttpServletRequest request) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Décision de droits d’usage indisponible",
                error.getMessage(),
                "INFRANEXUM_ENTITLEMENT_RUNTIME_UNAVAILABLE",
                request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String title, String detail, String code, HttpServletRequest request) {
        return problems.response(status, code, title, detail, Map.of(), Map.of(), request);
    }
}
