package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.ComplianceNotFoundException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for ITAM contractual-compliance boundaries. */
@Order(Ordered.HIGHEST_PRECEDENCE+52)
@RestControllerAdvice(assignableTypes=ItamComplianceController.class)
public final class ItamComplianceExceptionHandler {
    private final Clock clock;
    public ItamComplianceExceptionHandler(@Qualifier("platformClock") Clock clock){this.clock=Objects.requireNonNull(clock,"clock");}
    @ExceptionHandler(ComplianceNotFoundException.class) ResponseEntity<Problem> notFound(ComplianceNotFoundException f,HttpServletRequest r){return problem(HttpStatus.NOT_FOUND,"ITAM_COMPLIANCE_NOT_FOUND",f.getMessage(),r);}
    @ExceptionHandler(ComplianceConflictException.class) ResponseEntity<Problem> conflict(ComplianceConflictException f,HttpServletRequest r){HttpStatus status=switch(f.code()){case "ITAM_COMPLIANCE_CAPABILITY_UNAVAILABLE"->HttpStatus.FORBIDDEN;case "ITAM_COMPLIANCE_PARTNER_SCOPE_MISMATCH","ITAM_COMPLIANCE_PARTNER_NOT_AUTHORIZED","ITAM_WARRANTY_TYPE_INVALID","ITAM_SUPPORT_AUTH_SCOPE_MISMATCH","ITAM_SUPPORT_ESCALATION_CONTACT_MISSING","ITAM_SUPPORT_SUBDIVISION_INVALID","ITAM_SUPPORT_OBJECT_TYPE_INVALID"->HttpStatus.UNPROCESSABLE_CONTENT;default->HttpStatus.CONFLICT;};return problem(status,f.code(),f.getMessage(),r);}
    @ExceptionHandler(IdentityAccessException.class) ResponseEntity<Problem> denied(IdentityAccessException f,HttpServletRequest r){return problem(HttpStatus.FORBIDDEN,f.code(),"authorization denied",r);}
    @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class}) ResponseEntity<Problem> invalid(Exception f,HttpServletRequest r){return problem(HttpStatus.BAD_REQUEST,"ITAM_COMPLIANCE_INVALID_REQUEST",safe(f.getMessage()),r);}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class}) ResponseEntity<Problem> invalidBody(Exception f,HttpServletRequest r){return problem(HttpStatus.BAD_REQUEST,"ITAM_COMPLIANCE_INVALID_REQUEST","request body validation failed",r);}
    @ExceptionHandler(TransactionExecutionException.class) ResponseEntity<Problem> transaction(TransactionExecutionException f,HttpServletRequest r){return problem(HttpStatus.INTERNAL_SERVER_ERROR,"ITAM_COMPLIANCE_TRANSACTION_FAILED","Compliance transaction failed",r);}
    private ResponseEntity<Problem> problem(HttpStatus status,String code,String detail,HttpServletRequest request){Problem body=new Problem(URI.create("urn:infranexum:problem:"+code.toLowerCase(Locale.ROOT).replace('_','-')),"ITAM compliance request failed",status.value(),safe(detail),request.getRequestURI(),code,clock.instant(),CorrelationContext.traceId(request));return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);}
    private static String safe(String value){if(value==null||value.isBlank())return "request failed";String n=value.replaceAll("[\\r\\n\\t]+"," ").strip();return n.length()<=512?n:n.substring(0,512);}
    record Problem(URI type,String title,int status,String detail,String instance,String code,Instant occurredAt,String traceId){}
}
