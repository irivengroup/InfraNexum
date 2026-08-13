package io.infranexum.server.identity;

import io.infranexum.identity.local.domain.LocalAuthenticationException;
import io.infranexum.identity.local.domain.LocalPasswordPolicyException;
import io.infranexum.identity.local.domain.LocalSessionException;
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

/** Stable non-secret IAM error boundary. */
@RestControllerAdvice(assignableTypes = LocalAuthController.class)
public class LocalAuthExceptionHandler {
    private final Clock clock;
    public LocalAuthExceptionHandler(@Qualifier("platformClock") Clock clock) { this.clock = clock; }

    @ExceptionHandler(LocalAuthenticationException.class)
    ResponseEntity<Map<String,Object>> authentication(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "IAM_AUTHENTICATION_FAILED", "authentication failed", Map.of(), request);
    }

    @ExceptionHandler(LocalSessionException.class)
    ResponseEntity<Map<String,Object>> session(LocalSessionException failure, HttpServletRequest request) {
        HttpStatus status = failure.getMessage().startsWith("CSRF") ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        String code = status == HttpStatus.FORBIDDEN ? "IAM_CSRF_REJECTED" : "IAM_SESSION_INVALID";
        return error(status, code, status == HttpStatus.FORBIDDEN ? "request validation failed" : "session is invalid", Map.of(), request);
    }

    @ExceptionHandler(LocalPasswordPolicyException.class)
    ResponseEntity<Map<String,Object>> password(LocalPasswordPolicyException failure, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "IAM_LOCAL_PASSWORD_POLICY_VIOLATION", "local password policy violation",
                Map.of("violations", failure.violations()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> invalid(MethodArgumentNotValidException failure, HttpServletRequest request) {
        Map<String,Object> details = new LinkedHashMap<>();
        failure.getBindingResult().getFieldErrors().forEach(field -> details.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "IAM_INVALID_REQUEST", "request validation failed", details, request);
    }

    private ResponseEntity<Map<String,Object>> error(HttpStatus status, String code, String message, Map<String,?> details, HttpServletRequest request) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        String correlation = CorrelationContext.traceId(request);
        body.put("correlation_id", correlation == null ? "unavailable" : correlation);
        body.put("details", Map.copyOf(details));
        body.put("timestamp", clock.instant().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.copyOf(body));
    }
}
