package io.infranexum.server.rsot;

import io.infranexum.core.compatibility.SchemaRegistryException;
import io.infranexum.core.capabilities.CapabilityUnavailableException;
import io.infranexum.rsot.domain.RsotException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
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

/** RFC 9457 problem translation dedicated to schema-registry failures. */
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RestControllerAdvice(assignableTypes = {RsotSchemaController.class, RsotObjectController.class})
public final class RsotSchemaExceptionHandler {
    private final Clock clock;

    public RsotSchemaExceptionHandler(@Qualifier("platformClock") Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(SchemaRegistryException.class)
    ResponseEntity<Problem> registry(SchemaRegistryException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "SCHEMA_NOT_FOUND", "SCHEMA_PROFILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SCHEMA_VERSION_CONFLICT", "SCHEMA_PROFILE_VERSION_CONFLICT", "SCHEMA_REVISION_CONFLICT" -> HttpStatus.CONFLICT;
            case "SCHEMA_IMMUTABLE", "SCHEMA_PROFILE_IMMUTABLE", "SCHEMA_NOT_DRAFT", "SCHEMA_NOT_PUBLISHED",
                    "SCHEMA_PROFILE_NOT_PUBLISHED", "SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED",
                    "SCHEMA_BREAKING_APPROVAL_REQUIRED", "SCHEMA_COMPATIBILITY_INDETERMINATE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, failure.code(), safe(failure.getMessage()), request);
    }


    @ExceptionHandler(RsotException.class)
    ResponseEntity<Problem> canonical(RsotException failure, HttpServletRequest request) {
        HttpStatus status = "RSOT_CANONICAL_OBJECT_NOT_FOUND".equals(failure.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return problem(status, failure.code(), safe(failure.getMessage()), request);
    }

    @ExceptionHandler(CapabilityUnavailableException.class)
    ResponseEntity<Problem> capability(CapabilityUnavailableException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "RSOT_CORE_CAPABILITY_UNAVAILABLE",
                "RSOT core capability is unavailable for the active installation composition", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<Problem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RSOT_SCHEMA_INVALID_REQUEST", safe(failure.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RSOT_SCHEMA_INVALID_REQUEST", "request validation failed", request);
    }

    private ResponseEntity<Problem> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        String trace = CorrelationContext.traceId(request);
        Problem body = new Problem(URI.create("urn:infranexum:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                "RSOT schema registry request failed", status.value(), detail, request.getRequestURI(), code, clock.instant(), trace);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "request failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    record Problem(URI type, String title, int status, String detail, String instance, String code, Instant occurred_at, String trace_id) {}
}
