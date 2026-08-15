package io.infranexum.server.identityaccess;

import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable problem+json translation for IAM RBAC domain and validation failures. */
@RestControllerAdvice(assignableTypes = {IdentityAccessController.class, PolicyController.class})
public final class IdentityAccessExceptionHandler {
    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<ProblemDetail> domain(IdentityAccessException failure,HttpServletRequest request){
        String code=failure.code();
        HttpStatus status=code.endsWith("_NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : code.contains("AUTHORIZATION")||code.contains("FORBIDDEN")
                        ? HttpStatus.FORBIDDEN
                        : code.contains("CONFLICT")||code.contains("ASSIGNED")||code.contains("CYCLE")
                                ? HttpStatus.CONFLICT
                                : HttpStatus.UNPROCESSABLE_CONTENT;
        return problem(status,code,failure.getMessage(),request);
    }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> invalid(Exception failure,HttpServletRequest request){return problem(HttpStatus.BAD_REQUEST,"INFRANEXUM_IAM_INVALID_REQUEST","IAM request validation failed",request);}
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status,String code,String detail,HttpServletRequest request){ProblemDetail body=ProblemDetail.forStatusAndDetail(status,detail);body.setTitle("IAM request failed");body.setProperty("code",code);String correlation=CorrelationContext.traceId(request);if(correlation!=null){body.setProperty("correlation_id",correlation);body.setProperty("trace_id",correlation);}body.setProperty("metadata",Map.of());return ResponseEntity.status(status).body(body);}
}
