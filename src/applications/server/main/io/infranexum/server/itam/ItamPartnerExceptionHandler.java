package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerNotFoundException;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for ITAM Partner failures without leaking internal persistence details. */
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RestControllerAdvice(assignableTypes = ItamPartnerController.class)
public final class ItamPartnerExceptionHandler {
    private final ApiProblemSupport problems;

    public ItamPartnerExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(PartnerNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(PartnerNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ITAM_PARTNER_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(PartnerQuotaException.class)
    ResponseEntity<ApiProblem> quota(PartnerQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "ITAM_PARTNER_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(PartnerConflictException.class)
    ResponseEntity<ApiProblem> conflict(PartnerConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "ITAM_PARTNER_CAPABILITY_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "GOVERNING_ORGANIZATION_INVALID", "GOVERNING_SUBDIVISION_INVALID",
                    "PARTNER_AUTHORIZATION_PERIOD_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ApiProblem> authorization(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_PARTNER_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_PARTNER_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ITAM_PARTNER_TRANSACTION_FAILED", "Partner transaction failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "ITAM Partner request failed", detail, Map.of(), Map.of(), request);
    }
}
