package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerNotFoundException;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
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

/** RFC 9457 translation for ITAM Partner failures without leaking internal persistence details. */
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RestControllerAdvice(assignableTypes = ItamPartnerController.class)
public final class ItamPartnerExceptionHandler {
    private final Clock clock;

    public ItamPartnerExceptionHandler(@Qualifier("platformClock") Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(PartnerNotFoundException.class)
    ResponseEntity<Problem> notFound(PartnerNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ITAM_PARTNER_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(PartnerQuotaException.class)
    ResponseEntity<Problem> quota(PartnerQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "ITAM_PARTNER_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(PartnerConflictException.class)
    ResponseEntity<Problem> conflict(PartnerConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "ITAM_PARTNER_CAPABILITY_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "GOVERNING_ORGANIZATION_INVALID", "GOVERNING_SUBDIVISION_INVALID", "PARTNER_AUTHORIZATION_PERIOD_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
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
        return problem(HttpStatus.BAD_REQUEST, "ITAM_PARTNER_INVALID_REQUEST", safe(failure.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_PARTNER_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<Problem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ITAM_PARTNER_TRANSACTION_FAILED", "Partner transaction failed", request);
    }

    private ResponseEntity<Problem> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        String trace = CorrelationContext.traceId(request);
        Problem body = new Problem(
                URI.create("urn:infranexum:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
                "ITAM Partner request failed", status.value(), safe(detail), request.getRequestURI(), code, clock.instant(), trace);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "request failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    record Problem(URI type, String title, int status, String detail, String instance, String code, Instant occurredAt, String traceId) {}
}
