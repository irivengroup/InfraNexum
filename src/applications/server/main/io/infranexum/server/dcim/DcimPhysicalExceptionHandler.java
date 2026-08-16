package io.infranexum.server.dcim;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.dcim.physical.domain.DcimPhysicalConflictException;
import io.infranexum.dcim.physical.domain.DcimPhysicalNotFoundException;
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

/** RFC 9457 translation for PGM-07-E05 physical DCIM failures. */
@Order(Ordered.HIGHEST_PRECEDENCE + 55)
@RestControllerAdvice(assignableTypes = DcimPhysicalController.class)
public final class DcimPhysicalExceptionHandler {
    private final ApiProblemSupport problems;

    public DcimPhysicalExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(DcimPhysicalNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(DcimPhysicalNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "DCIM_PHYSICAL_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(DcimPhysicalConflictException.class)
    ResponseEntity<ApiProblem> conflict(DcimPhysicalConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "DCIM_PHYSICAL_CAPABILITY_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "DCIM_ORGANIZATION_INVALID", "DCIM_ORGANIZATION_INACTIVE", "DCIM_SUBDIVISION_INVALID",
                    "DCIM_SUBDIVISION_INACTIVE", "DCIM_ROOM_INVALID", "DCIM_ROOM_INACTIVE",
                    "DCIM_MANUFACTURER_INVALID", "DCIM_RSOT_INVALID", "DCIM_ITAM_ASSET_INVALID",
                    "DCIM_SCOPE_MISMATCH", "DCIM_MODEL_INACTIVE", "DCIM_RACK_INACTIVE",
                    "DCIM_FOOTPRINT_INCOMPATIBLE", "DCIM_PORT_KIND_MISMATCH",
                    "DCIM_PORT_MEDIA_MISMATCH" -> HttpStatus.UNPROCESSABLE_CONTENT;
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
        return problem(HttpStatus.BAD_REQUEST, "DCIM_PHYSICAL_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DCIM_PHYSICAL_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "DCIM_PHYSICAL_TRANSACTION_FAILED", "DCIM transaction failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "DCIM physical request failed", detail, Map.of(), Map.of(), request);
    }
}
