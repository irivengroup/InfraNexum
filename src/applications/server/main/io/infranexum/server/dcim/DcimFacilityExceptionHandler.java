package io.infranexum.server.dcim;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityNotFoundException;
import io.infranexum.dcim.facility.domain.FacilityQuotaException;
import io.infranexum.identity.access.domain.IdentityAccessException;
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

/** RFC 9457 translation for PGM-07-E04 DCIM facility failures. */
@Order(Ordered.HIGHEST_PRECEDENCE + 54)
@RestControllerAdvice(assignableTypes = DcimFacilityController.class)
public final class DcimFacilityExceptionHandler {
    private final ApiProblemSupport problems;

    public DcimFacilityExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(FacilityNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(FacilityNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "DCIM_FACILITY_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(FacilityQuotaException.class)
    ResponseEntity<ApiProblem> quota(FacilityQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "DCIM_FACILITY_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(FacilityConflictException.class)
    ResponseEntity<ApiProblem> conflict(FacilityConflictException failure, HttpServletRequest request) {
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
    ResponseEntity<ApiProblem> authorization(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DCIM_FACILITY_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DCIM_FACILITY_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "DCIM_FACILITY_TRANSACTION_FAILED", "DCIM transaction failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "DCIM facility request failed", detail, Map.of(), Map.of(), request);
    }
}
