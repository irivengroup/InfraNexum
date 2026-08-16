package io.infranexum.server.rsot;

import io.infranexum.core.capabilities.CapabilityUnavailableException;
import io.infranexum.core.compatibility.SchemaRegistryException;
import io.infranexum.rsot.domain.RsotException;
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

/** RFC 9457 problem translation dedicated to schema-registry failures. */
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RestControllerAdvice(assignableTypes = {RsotSchemaController.class, RsotObjectController.class})
public final class RsotSchemaExceptionHandler {
    private final ApiProblemSupport problems;

    public RsotSchemaExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(SchemaRegistryException.class)
    ResponseEntity<ApiProblem> registry(SchemaRegistryException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "SCHEMA_NOT_FOUND", "SCHEMA_PROFILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SCHEMA_VERSION_CONFLICT", "SCHEMA_PROFILE_VERSION_CONFLICT", "SCHEMA_REVISION_CONFLICT" -> HttpStatus.CONFLICT;
            case "SCHEMA_IMMUTABLE", "SCHEMA_PROFILE_IMMUTABLE", "SCHEMA_NOT_DRAFT", "SCHEMA_NOT_PUBLISHED",
                    "SCHEMA_PROFILE_NOT_PUBLISHED", "SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED",
                    "SCHEMA_BREAKING_APPROVAL_REQUIRED", "SCHEMA_COMPATIBILITY_INDETERMINATE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(RsotException.class)
    ResponseEntity<ApiProblem> canonical(RsotException failure, HttpServletRequest request) {
        HttpStatus status = "RSOT_CANONICAL_OBJECT_NOT_FOUND".equals(failure.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(CapabilityUnavailableException.class)
    ResponseEntity<ApiProblem> capability(CapabilityUnavailableException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "RSOT_CORE_CAPABILITY_UNAVAILABLE",
                "RSOT core capability is unavailable for the active installation composition", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RSOT_SCHEMA_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RSOT_SCHEMA_INVALID_REQUEST", "request validation failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "RSOT schema registry request failed", detail, Map.of(), Map.of(), request);
    }
}
