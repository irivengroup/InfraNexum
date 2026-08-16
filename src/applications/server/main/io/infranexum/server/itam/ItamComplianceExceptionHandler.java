package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.ComplianceNotFoundException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for ITAM contractual-compliance boundaries. */
@Order(Ordered.HIGHEST_PRECEDENCE + 52)
@RestControllerAdvice(assignableTypes = ItamComplianceController.class)
public final class ItamComplianceExceptionHandler {
    private final ApiProblemSupport problems;

    public ItamComplianceExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(ComplianceNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(ComplianceNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ITAM_COMPLIANCE_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(ComplianceConflictException.class)
    ResponseEntity<ApiProblem> conflict(ComplianceConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "ITAM_COMPLIANCE_CAPABILITY_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "ITAM_COMPLIANCE_PARTNER_SCOPE_MISMATCH", "ITAM_COMPLIANCE_PARTNER_NOT_AUTHORIZED",
                    "ITAM_WARRANTY_TYPE_INVALID", "ITAM_SUPPORT_AUTH_SCOPE_MISMATCH",
                    "ITAM_SUPPORT_ESCALATION_CONTACT_MISSING", "ITAM_SUPPORT_SUBDIVISION_INVALID",
                    "ITAM_SUPPORT_OBJECT_TYPE_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ApiProblem> denied(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_COMPLIANCE_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiProblem> invalidBody(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_COMPLIANCE_INVALID_REQUEST", "request body validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ITAM_COMPLIANCE_TRANSACTION_FAILED", "Compliance transaction failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "ITAM compliance request failed", detail, Map.of(), Map.of(), request);
    }
}
