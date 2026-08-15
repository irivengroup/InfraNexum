package io.infranexum.server.dcim;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityNotFoundException;
import io.infranexum.dcim.facility.domain.FacilityQuotaException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for PGM-07-E04 DCIM facility failures. */
@Order(Ordered.HIGHEST_PRECEDENCE + 54)
@RestControllerAdvice(assignableTypes = DcimFacilityController.class)
public final class DcimFacilityExceptionHandler {
    private final Clock clock;

    public DcimFacilityExceptionHandler(@Qualifier("platformClock") Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(FacilityNotFoundException.class)
    ResponseEntity<Problem> notFound(FacilityNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "DCIM_FACILITY_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(FacilityQuotaException.class)
    ResponseEntity<Problem> quota(FacilityQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "DCIM_FACILITY_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(FacilityConflictException.class)
    ResponseEntity<Problem> conflict(FacilityConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "DCIM_FACILITY_CAPABILITY_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "DCIM_ORGANIZATION_INVALID", "DCIM_ORGANIZATION_INACTIVE", "DCIM_SUBDIVISION_INVALID",
                    "DCIM_SUBDIVISION_INACTIVE", "DCIM_PARENT_KIND_INVALID", "DCIM_ZONE_PARENT_INVALID",
                    "DCIM_SCOPE_MISMATCH", "DCIM_PARENT_INACTIVE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<Problem> authorization(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<Problem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DCIM_FACILITY_INVALID_REQUEST", safe(failure.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DCIM_FACILITY_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<Problem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "DCIM_FACILITY_TRANSACTION_FAILED", "DCIM transaction failed", request);
    }

    private ResponseEntity<Problem> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        Problem body = new Problem(
                URI.create("urn:infranexum:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
                "DCIM facility request failed", status.value(), safe(detail), request.getRequestURI(), code,
                clock.instant(), CorrelationContext.traceId(request));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "request failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    record Problem(URI type, String title, int status, String detail, String instance, String code, Instant occurredAt, String traceId) {}
}
