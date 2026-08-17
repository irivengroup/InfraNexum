package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JiraAssetsAuthenticationException;
import io.infranexum.adapters.jiraassets.JiraAssetsProtocolException;
import io.infranexum.adapters.jiraassets.JiraAssetsRateLimitedException;
import io.infranexum.adapters.jiraassets.JiraAssetsUnavailableException;
import io.infranexum.adapters.servicenow.ServiceNowAuthenticationException;
import io.infranexum.adapters.servicenow.ServiceNowProtocolException;
import io.infranexum.adapters.servicenow.ServiceNowRateLimitedException;
import io.infranexum.adapters.servicenow.ServiceNowUnavailableException;
import io.infranexum.integrations.ConnectorDeliveryNotFoundException;
import io.infranexum.integrations.ConnectorEndpointUnavailableException;
import io.infranexum.integrations.ConnectorDeliveryStateConflictException;
import io.infranexum.integrations.DuplicateDeliveryConflictException;
import io.infranexum.integrations.WebhookAuthenticationException;
import io.infranexum.integrations.OutboundNotificationNotFoundException;
import io.infranexum.integrations.OutboundNotificationStateConflictException;
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

/** Canonical RFC 9457 translation for PGM-10-E05/E06 integration failures. */
@RestControllerAdvice(assignableTypes={IntegrationController.class,JiraAssetsController.class,ServiceNowController.class,NotificationController.class})
final class IntegrationExceptionHandler {
    private final ApiProblemSupport problems;
    IntegrationExceptionHandler(ApiProblemSupport problems){this.problems=Objects.requireNonNull(problems,"problems");}
    @ExceptionHandler(WebhookAuthenticationException.class) ResponseEntity<ApiProblem> auth(WebhookAuthenticationException failure,HttpServletRequest request){return problem(HttpStatus.UNAUTHORIZED,"INFRANEXUM_WEBHOOK_AUTHENTICATION_FAILED","Webhook authentication failed",request);}
    @ExceptionHandler(ConnectorEndpointUnavailableException.class) ResponseEntity<ApiProblem> endpoint(ConnectorEndpointUnavailableException failure,HttpServletRequest request){return problem(HttpStatus.NOT_FOUND,"INFRANEXUM_CONNECTOR_ENDPOINT_UNAVAILABLE","Connector endpoint is unavailable",request);}
    @ExceptionHandler(ConnectorDeliveryNotFoundException.class) ResponseEntity<ApiProblem> missing(ConnectorDeliveryNotFoundException failure,HttpServletRequest request){return problem(HttpStatus.NOT_FOUND,"INFRANEXUM_CONNECTOR_DELIVERY_NOT_FOUND","Connector delivery was not found",request);}
    @ExceptionHandler({DuplicateDeliveryConflictException.class,ConnectorDeliveryStateConflictException.class}) ResponseEntity<ApiProblem> conflict(RuntimeException failure,HttpServletRequest request){return problem(HttpStatus.CONFLICT,"INFRANEXUM_CONNECTOR_DELIVERY_CONFLICT","Connector delivery state conflicts with the requested operation",request);}
    @ExceptionHandler(OutboundNotificationNotFoundException.class) ResponseEntity<ApiProblem> notificationMissing(OutboundNotificationNotFoundException failure,HttpServletRequest request){return problem(HttpStatus.NOT_FOUND,"INFRANEXUM_NOTIFICATION_NOT_FOUND","Notification delivery or endpoint was not found",request);}
    @ExceptionHandler(OutboundNotificationStateConflictException.class) ResponseEntity<ApiProblem> notificationConflict(OutboundNotificationStateConflictException failure,HttpServletRequest request){return problem(HttpStatus.CONFLICT,"INFRANEXUM_NOTIFICATION_STATE_CONFLICT","Notification state conflicts with the requested operation",request);}
    @ExceptionHandler({IllegalArgumentException.class,MissingRequestHeaderException.class}) ResponseEntity<ApiProblem> invalid(Exception failure,HttpServletRequest request){return problem(HttpStatus.BAD_REQUEST,"INFRANEXUM_CONNECTOR_INVALID_REQUEST","Connector request is invalid",request);}
    @ExceptionHandler(JiraAssetsAuthenticationException.class) ResponseEntity<ApiProblem> jiraAuth(JiraAssetsAuthenticationException failure,HttpServletRequest request){return problem(HttpStatus.BAD_GATEWAY,"INFRANEXUM_JIRA_ASSETS_AUTHENTICATION_FAILED","Jira Assets rejected the configured connector credential or read scopes",request);}
    @ExceptionHandler(JiraAssetsRateLimitedException.class) ResponseEntity<ApiProblem> jiraRate(JiraAssetsRateLimitedException failure,HttpServletRequest request){return problem(HttpStatus.SERVICE_UNAVAILABLE,"INFRANEXUM_JIRA_ASSETS_RATE_LIMITED","Jira Assets temporarily rate limited the connector",request);}
    @ExceptionHandler(JiraAssetsUnavailableException.class) ResponseEntity<ApiProblem> jiraUnavailable(JiraAssetsUnavailableException failure,HttpServletRequest request){return problem(HttpStatus.SERVICE_UNAVAILABLE,"INFRANEXUM_JIRA_ASSETS_UNAVAILABLE","Jira Assets is temporarily unavailable",request);}
    @ExceptionHandler(JiraAssetsProtocolException.class) ResponseEntity<ApiProblem> jiraProtocol(JiraAssetsProtocolException failure,HttpServletRequest request){return problem(HttpStatus.BAD_GATEWAY,"INFRANEXUM_JIRA_ASSETS_PROTOCOL_ERROR","Jira Assets returned an unsupported response",request);}
    @ExceptionHandler(ServiceNowAuthenticationException.class) ResponseEntity<ApiProblem> serviceNowAuth(ServiceNowAuthenticationException failure,HttpServletRequest request){return problem(HttpStatus.BAD_GATEWAY,"INFRANEXUM_SERVICE_NOW_AUTHENTICATION_FAILED","ServiceNow rejected the configured OAuth bearer token or CMDB read ACLs",request);}
    @ExceptionHandler(ServiceNowRateLimitedException.class) ResponseEntity<ApiProblem> serviceNowRate(ServiceNowRateLimitedException failure,HttpServletRequest request){return problem(HttpStatus.SERVICE_UNAVAILABLE,"INFRANEXUM_SERVICE_NOW_RATE_LIMITED","ServiceNow temporarily rate limited the connector",request);}
    @ExceptionHandler(ServiceNowUnavailableException.class) ResponseEntity<ApiProblem> serviceNowUnavailable(ServiceNowUnavailableException failure,HttpServletRequest request){return problem(HttpStatus.SERVICE_UNAVAILABLE,"INFRANEXUM_SERVICE_NOW_UNAVAILABLE","ServiceNow is temporarily unavailable",request);}
    @ExceptionHandler(ServiceNowProtocolException.class) ResponseEntity<ApiProblem> serviceNowProtocol(ServiceNowProtocolException failure,HttpServletRequest request){return problem(HttpStatus.BAD_GATEWAY,"INFRANEXUM_SERVICE_NOW_PROTOCOL_ERROR","ServiceNow returned an unsupported response",request);}
    private ResponseEntity<ApiProblem> problem(HttpStatus status,String code,String detail,HttpServletRequest request){return problems.response(status,code,"Integration request failed",detail,Map.of(),Map.of(),request);}
}
