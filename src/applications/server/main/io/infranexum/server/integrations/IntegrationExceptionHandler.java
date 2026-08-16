package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorDeliveryNotFoundException;
import io.infranexum.integrations.ConnectorEndpointUnavailableException;
import io.infranexum.integrations.ConnectorDeliveryStateConflictException;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.WebhookAuthenticationException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical RFC 9457 translation for PGM-10-E05 integration failures. */
@RestControllerAdvice(assignableTypes=IntegrationController.class)
final class IntegrationExceptionHandler {
    private final ApiProblemSupport problems;
    IntegrationExceptionHandler(ApiProblemSupport problems){this.problems=Objects.requireNonNull(problems,"problems");}
    @ExceptionHandler(WebhookAuthenticationException.class) ResponseEntity<ApiProblem> auth(WebhookAuthenticationException failure,HttpServletRequest request){return problem(HttpStatus.UNAUTHORIZED,"INFRANEXUM_WEBHOOK_AUTHENTICATION_FAILED","Webhook authentication failed",request);}
    @ExceptionHandler(ConnectorEndpointUnavailableException.class) ResponseEntity<ApiProblem> endpoint(ConnectorEndpointUnavailableException failure,HttpServletRequest request){return problem(HttpStatus.NOT_FOUND,"INFRANEXUM_CONNECTOR_ENDPOINT_UNAVAILABLE","Connector endpoint is unavailable",request);}
    @ExceptionHandler(ConnectorDeliveryNotFoundException.class) ResponseEntity<ApiProblem> missing(ConnectorDeliveryNotFoundException failure,HttpServletRequest request){return problem(HttpStatus.NOT_FOUND,"INFRANEXUM_CONNECTOR_DELIVERY_NOT_FOUND","Connector delivery was not found",request);}
    @ExceptionHandler({DuplicateDeliveryConflictException.class,ConnectorDeliveryStateConflictException.class}) ResponseEntity<ApiProblem> conflict(RuntimeException failure,HttpServletRequest request){return problem(HttpStatus.CONFLICT,"INFRANEXUM_CONNECTOR_DELIVERY_CONFLICT","Connector delivery state conflicts with the requested operation",request);}
    @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class}) ResponseEntity<ApiProblem> invalid(Exception failure,HttpServletRequest request){return problem(HttpStatus.BAD_REQUEST,"INFRANEXUM_CONNECTOR_INVALID_REQUEST","Connector request is invalid",request);}
    private ResponseEntity<ApiProblem> problem(HttpStatus status,String code,String detail,HttpServletRequest request){return problems.response(status,code,"Integration request failed",detail,Map.of(),Map.of(),request);}
}
