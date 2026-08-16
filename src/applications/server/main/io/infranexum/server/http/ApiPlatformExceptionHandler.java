package io.infranexum.server.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Last-resort API problem translator.
 *
 * <p>Domain-specific advices remain authoritative because this advice has the lowest precedence.
 * It prevents Spring default/HTML error bodies from escaping registered Server controllers and
 * never exposes exception messages for unexpected failures.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "io.infranexum.server")
public final class ApiPlatformExceptionHandler {
    private final ApiProblemSupport problems;

    public ApiPlatformExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> validation(MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "INFRANEXUM_INVALID_REQUEST", "Invalid request",
                "request validation failed", details, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiProblem> malformed(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INFRANEXUM_INVALID_REQUEST", "Invalid request",
                "request syntax or required input is invalid", Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> method(HttpRequestMethodNotSupportedException failure, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "INFRANEXUM_METHOD_NOT_ALLOWED", "Method not allowed",
                "HTTP method is not supported for this resource", Map.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblem> mediaType(HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INFRANEXUM_MEDIA_TYPE_UNSUPPORTED", "Unsupported media type",
                "request media type is not supported", Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblem> missing(NoResourceFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "INFRANEXUM_RESOURCE_NOT_FOUND", "Resource not found",
                "requested resource was not found", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INFRANEXUM_INTERNAL_ERROR", "Internal server error",
                "request failed closed", Map.of(), request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, ?> details,
            HttpServletRequest request) {
        return problems.response(status, code, title, detail, details, Map.of(), request);
    }
}
