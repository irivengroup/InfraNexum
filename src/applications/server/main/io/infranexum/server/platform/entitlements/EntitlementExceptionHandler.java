package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementAccessException;
import io.infranexum.core.entitlements.EntitlementRuntimeUnavailableException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates entitlement failures into stable, non-sensitive RFC problem responses. */
@ConditionalOnProperty(
        name = "infranexum.entitlements.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RestControllerAdvice
public final class EntitlementExceptionHandler {
    private final Clock clock;

    public EntitlementExceptionHandler(@Qualifier("entitlementClock") Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(EntitlementAccessException.class)
    public ResponseEntity<EntitlementProblem> handleAccess(
            EntitlementAccessException error, HttpServletRequest request) {
        return problem(
                HttpStatus.FORBIDDEN,
                "urn:infranexum:problem:entitlement-access-denied",
                "Opération interdite par les droits d’usage",
                error.getMessage(),
                error.code().value(),
                request);
    }

    @ExceptionHandler(EntitlementRuntimeUnavailableException.class)
    public ResponseEntity<EntitlementProblem> handleUnavailable(
            EntitlementRuntimeUnavailableException error, HttpServletRequest request) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "urn:infranexum:problem:entitlement-runtime-unavailable",
                "Décision de droits d’usage indisponible",
                error.getMessage(),
                "INFRANEXUM_ENTITLEMENT_RUNTIME_UNAVAILABLE",
                request);
    }

    private ResponseEntity<EntitlementProblem> problem(
            HttpStatus status,
            String type,
            String title,
            String detail,
            String code,
            HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        String traceId = CorrelationContext.traceId(request);
        var body = new EntitlementProblem(
                URI.create(type),
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                clock.instant(),
                traceId);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    /** Canonical entitlement problem with controlled InfraNexum extensions. */
    public record EntitlementProblem(
            URI type,
            String title,
            int status,
            String detail,
            String instance,
            String code,
            Instant occurred_at,
            String trace_id) {}
}
