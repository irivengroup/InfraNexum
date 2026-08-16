package io.infranexum.server.identity;

import io.infranexum.identity.local.domain.LocalAuthenticationException;
import io.infranexum.identity.local.domain.LocalPasswordPolicyException;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable non-secret RFC 9457 boundary for local authentication failures. */
@RestControllerAdvice(assignableTypes = LocalAuthController.class)
public final class LocalAuthExceptionHandler {
    private final ApiProblemSupport problems;

    public LocalAuthExceptionHandler(ApiProblemSupport problems) {
        this.problems = java.util.Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(LocalAuthenticationException.class)
    ResponseEntity<ApiProblem> authentication(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "IAM_AUTHENTICATION_FAILED", "Authentication failed",
                "authentication failed", Map.of(), request);
    }

    @ExceptionHandler(LocalSessionException.class)
    ResponseEntity<ApiProblem> session(LocalSessionException failure, HttpServletRequest request) {
        HttpStatus status = failure.getMessage().startsWith("CSRF") ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        String code = status == HttpStatus.FORBIDDEN ? "IAM_CSRF_REJECTED" : "IAM_SESSION_INVALID";
        String detail = status == HttpStatus.FORBIDDEN ? "request validation failed" : "session is invalid";
        return error(status, code, "Local authentication request failed", detail, Map.of(), request);
    }

    @ExceptionHandler(LocalPasswordPolicyException.class)
    ResponseEntity<ApiProblem> password(LocalPasswordPolicyException failure, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "IAM_LOCAL_PASSWORD_POLICY_VIOLATION",
                "Local password policy violation", "local password policy violation",
                Map.of("violations", failure.violations()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalid(MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(field ->
                details.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "IAM_INVALID_REQUEST", "Invalid authentication request",
                "request validation failed", details, request);
    }

    private ResponseEntity<ApiProblem> error(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, ?> details,
            HttpServletRequest request) {
        return problems.response(status, code, title, detail, details, Map.of(), request);
    }
}
