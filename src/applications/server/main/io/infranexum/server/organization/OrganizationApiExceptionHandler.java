package io.infranexum.server.organization;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationNotFoundException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationStateException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical RFC 9457 problem boundary for Organization APIs. */
@RestControllerAdvice(assignableTypes = OrganizationController.class)
public final class OrganizationApiExceptionHandler {
    private final ApiProblemSupport problems;

    public OrganizationApiExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(OrganizationNotFoundException failure, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler({OrganizationConflictException.class, OrganizationStateException.class})
    ResponseEntity<ApiProblem> conflict(RuntimeException failure, HttpServletRequest request) {
        String code = failure instanceof OrganizationConflictException conflict
                ? conflict.code()
                : "ORG_STATE_CONFLICT";
        return error(HttpStatus.CONFLICT, code, failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(OrganizationQuotaException.class)
    ResponseEntity<ApiProblem> quota(OrganizationQuotaException failure, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "ORG_QUOTA_EXCEEDED", failure.getMessage(),
                Map.of("quota_key", failure.quotaKey()), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    ResponseEntity<ApiProblem> invalid(RuntimeException failure, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "ORG_INVALID_REQUEST", failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(fieldError ->
                details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "ORG_INVALID_REQUEST", "request validation failed", details, request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transactionFailure(TransactionExecutionException failure, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "ORG_TRANSACTION_FAILED",
                "organization transaction failed", Map.of(), request);
    }

    private ResponseEntity<ApiProblem> error(
            HttpStatus status, String code, String detail, Map<String, ?> details, HttpServletRequest request) {
        return problems.response(status, code, "Organization request failed", detail, details, Map.of(), request);
    }
}
