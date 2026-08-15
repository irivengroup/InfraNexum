package io.infranexum.server.dcim;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.dcim.physical.domain.DcimPhysicalConflictException;
import io.infranexum.dcim.physical.domain.DcimPhysicalNotFoundException;
import io.infranexum.identity.access.domain.IdentityAccessException;
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
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for PGM-07-E05 physical DCIM failures. */
@Order(Ordered.HIGHEST_PRECEDENCE+55)
@RestControllerAdvice(assignableTypes=DcimPhysicalController.class)
public final class DcimPhysicalExceptionHandler {
 private final Clock clock; public DcimPhysicalExceptionHandler(@Qualifier("platformClock") Clock clock){this.clock=Objects.requireNonNull(clock,"clock");}
 @ExceptionHandler(DcimPhysicalNotFoundException.class) ResponseEntity<Problem> notFound(DcimPhysicalNotFoundException x,HttpServletRequest r){return problem(HttpStatus.NOT_FOUND,"DCIM_PHYSICAL_NOT_FOUND",x.getMessage(),r);}
 @ExceptionHandler(DcimPhysicalConflictException.class) ResponseEntity<Problem> conflict(DcimPhysicalConflictException x,HttpServletRequest r){HttpStatus status=switch(x.code()){case "DCIM_PHYSICAL_CAPABILITY_UNAVAILABLE"->HttpStatus.FORBIDDEN;case "DCIM_ORGANIZATION_INVALID","DCIM_ORGANIZATION_INACTIVE","DCIM_SUBDIVISION_INVALID","DCIM_SUBDIVISION_INACTIVE","DCIM_ROOM_INVALID","DCIM_ROOM_INACTIVE","DCIM_MANUFACTURER_INVALID","DCIM_RSOT_INVALID","DCIM_ITAM_ASSET_INVALID","DCIM_SCOPE_MISMATCH","DCIM_MODEL_INACTIVE","DCIM_RACK_INACTIVE","DCIM_FOOTPRINT_INCOMPATIBLE","DCIM_PORT_KIND_MISMATCH","DCIM_PORT_MEDIA_MISMATCH"->HttpStatus.UNPROCESSABLE_CONTENT;default->HttpStatus.CONFLICT;};return problem(status,x.code(),x.getMessage(),r);}
 @ExceptionHandler(IdentityAccessException.class) ResponseEntity<Problem> auth(IdentityAccessException x,HttpServletRequest r){return problem(HttpStatus.FORBIDDEN,x.code(),"authorization denied",r);}
 @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class}) ResponseEntity<Problem> invalid(Exception x,HttpServletRequest r){return problem(HttpStatus.BAD_REQUEST,"DCIM_PHYSICAL_INVALID_REQUEST",safe(x.getMessage()),r);} @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException x,HttpServletRequest r){return problem(HttpStatus.BAD_REQUEST,"DCIM_PHYSICAL_INVALID_REQUEST","request validation failed",r);} @ExceptionHandler(TransactionExecutionException.class) ResponseEntity<Problem> tx(TransactionExecutionException x,HttpServletRequest r){return problem(HttpStatus.INTERNAL_SERVER_ERROR,"DCIM_PHYSICAL_TRANSACTION_FAILED","DCIM transaction failed",r);}
 private ResponseEntity<Problem> problem(HttpStatus status,String code,String detail,HttpServletRequest r){var body=new Problem(URI.create("urn:infranexum:problem:"+code.toLowerCase(Locale.ROOT).replace('_','-')),"DCIM physical request failed",status.value(),safe(detail),r.getRequestURI(),code,clock.instant(),CorrelationContext.traceId(r));return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);} private static String safe(String x){if(x==null||x.isBlank())return "request failed";String n=x.replaceAll("[\\r\\n\\t]+"," ").strip();return n.length()<=512?n:n.substring(0,512);} record Problem(URI type,String title,int status,String detail,String instance,String code,Instant occurredAt,String traceId){}
}
