package io.infranexum.server.ddi;

import io.infranexum.ddi.ipam.domain.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=IpamController.class)
final class IpamExceptionHandler {@ExceptionHandler(IpamNotFoundException.class) ResponseEntity<ProblemDetail> missing(IpamNotFoundException e){return problem(HttpStatus.NOT_FOUND,"DDI_IPAM_NOT_FOUND",e.getMessage());}@ExceptionHandler(IpamConflictException.class) ResponseEntity<ProblemDetail> conflict(IpamConflictException e){HttpStatus s=e.code().endsWith("CAPABILITY_UNAVAILABLE")?HttpStatus.FORBIDDEN:HttpStatus.CONFLICT;return problem(s,e.code(),e.getMessage());}@ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e){return problem(HttpStatus.BAD_REQUEST,"DDI_IPAM_INVALID_REQUEST",e.getMessage());}private static ResponseEntity<ProblemDetail> problem(HttpStatus s,String code,String detail){ProblemDetail p=ProblemDetail.forStatusAndDetail(s,detail);p.setType(URI.create("urn:infranexum:problem:"+code.toLowerCase(java.util.Locale.ROOT)));p.setProperty("code",code);return ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}}
