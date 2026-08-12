package io.infranexum.server.organization;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.organization.domain.OrganizationConflictException;
import io.infranexum.organization.domain.OrganizationNotFoundException;
import io.infranexum.organization.domain.OrganizationQuotaException;
import io.infranexum.organization.domain.OrganizationStateException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable non-sensitive error envelope for the Organization HTTP boundary. */
@RestControllerAdvice(assignableTypes = OrganizationController.class)
public final class OrganizationApiExceptionHandler {
    private final Clock clock;

    public OrganizationApiExceptionHandler(@Qualifier("platformClock") Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(
            OrganizationNotFoundException failure, HttpServletRequest request) {
        return error(
                HttpStatus.NOT_FOUND,
                "ORG_NOT_FOUND",
                failure.getMessage(),
                Map.of(),
                request);
    }

    @ExceptionHandler({OrganizationConflictException.class, OrganizationStateException.class})
    ResponseEntity<Map<String, Object>> conflict(
            RuntimeException failure, HttpServletRequest request) {
        String code = failure instanceof OrganizationConflictException conflict
                ? conflict.code()
                : "ORG_STATE_CONFLICT";
        return error(HttpStatus.CONFLICT, code, failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(OrganizationQuotaException.class)
    ResponseEntity<Map<String, Object>> quota(
            OrganizationQuotaException failure, HttpServletRequest request) {
        return error(
                HttpStatus.CONFLICT,
                "ORG_QUOTA_EXCEEDED",
                failure.getMessage(),
                Map.of("quota_key", failure.quotaKey()),
                request);
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> invalid(
            RuntimeException failure, HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "ORG_INVALID_REQUEST",
                failure.getMessage(),
                Map.of(),
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(
            MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(fieldError ->
                details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        return error(
                HttpStatus.BAD_REQUEST,
                "ORG_INVALID_REQUEST",
                "request validation failed",
                details,
                request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<Map<String, Object>> transactionFailure(
            TransactionExecutionException failure, HttpServletRequest request) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ORG_TRANSACTION_FAILED",
                "organization transaction failed",
                Map.of(),
                request);
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String code,
            String message,
            Map<String, ?> details,
            HttpServletRequest request) {
        String correlationId = CorrelationContext.traceId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "request failed" : message);
        body.put("correlation_id", correlationId == null ? "unavailable" : correlationId);
        body.put("details", Map.copyOf(details));
        body.put("timestamp", clock.instant().toString());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.copyOf(body));
    }
}
