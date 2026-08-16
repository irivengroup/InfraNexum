package io.infranexum.server.identityaccess;

import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical RFC 9457 translation for IAM RBAC and policy failures. */
@RestControllerAdvice(assignableTypes = {IdentityAccessController.class, PolicyController.class})
public final class IdentityAccessExceptionHandler {
    private final ApiProblemSupport problems;

    public IdentityAccessExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ApiProblem> domain(IdentityAccessException failure, HttpServletRequest request) {
        String code = failure.code();
        HttpStatus status = code.endsWith("_NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : code.contains("AUTHORIZATION") || code.contains("FORBIDDEN")
                        ? HttpStatus.FORBIDDEN
                        : code.contains("CONFLICT") || code.contains("ASSIGNED") || code.contains("CYCLE")
                                ? HttpStatus.CONFLICT
                                : HttpStatus.UNPROCESSABLE_CONTENT;
        return problem(status, code, failure.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INFRANEXUM_IAM_INVALID_REQUEST",
                "IAM request validation failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "IAM request failed", detail, Map.of(), Map.of(), request);
    }
}
