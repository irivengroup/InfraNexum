package io.infranexum.server.integrations;

import static io.infranexum.server.integrations.NotificationApiModels.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.server.http.ApiPagination;
import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Authenticated HTTP boundary for signed outbound operational notifications. */
@RestController
@ConditionalOnProperty(name="infranexum.integrations.enabled",havingValue="true")
public final class NotificationController {
    private final NotificationOperationsService operations;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationController(NotificationOperationsService operations,ObjectMapper json,@Qualifier("platformClock") Clock clock){this.operations=Objects.requireNonNull(operations,"operations");this.json=Objects.requireNonNull(json,"json");this.clock=Objects.requireNonNull(clock,"clock");}

    @GetMapping("/api/v1/integrations/notifications/endpoints")
    ResponseEntity<List<EndpointResponse>> endpoints(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){var page=operations.endpoints(offset,limit);return ApiPagination.offset(page.items().stream().map(EndpointResponse::from).toList(),page.nextOffset(),limit);}

    @PostMapping("/api/v1/integrations/notifications/events")
    ResponseEntity<List<AdmissionResponse>> publish(@RequestBody PublishRequest body,HttpServletRequest request){Objects.requireNonNull(body,"body");if(body.payload()==null||(!body.payload().isObject()&&!body.payload().isArray()))throw new IllegalArgumentException("notification payload root must be a JSON object or array");if(body.endpointKeys()==null)throw new IllegalArgumentException("endpointKeys are required");byte[] payload;try{payload=json.writeValueAsBytes(body.payload());}catch(JacksonException failure){throw new IllegalArgumentException("notification payload is invalid JSON",failure);}List<ConnectorKey> keys=body.endpointKeys().stream().map(ConnectorKey::new).toList();var result=operations.publish(body.eventId(),body.eventType(),payload,keys,actor(request),correlation(request)).stream().map(AdmissionResponse::from).toList();return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control","no-store").body(result);}

    @GetMapping("/api/v1/integrations/notifications/dlq")
    ResponseEntity<List<DeadLetterResponse>> deadLetters(@RequestParam(required=false) String endpointKey,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){ConnectorKey key=endpointKey==null?null:new ConnectorKey(endpointKey);var page=operations.deadLetters(key,offset,limit);return ApiPagination.offset(page.items().stream().map(DeadLetterResponse::from).toList(),page.nextOffset(),limit);}

    @PostMapping("/api/v1/integrations/notifications/dlq/{deliveryId}/replay")
    DeadLetterResponse replay(@PathVariable String deliveryId,@RequestBody(required=false) ReasonRequest body,HttpServletRequest request){return DeadLetterResponse.from(operations.replay(DomainIdentifier.parse(deliveryId),actor(request),correlation(request),normalizeReason(body==null?null:body.reason())));}

    @GetMapping("/api/v1/integrations/notifications/endpoints/{endpointKey}/runtime")
    RuntimeResponse runtime(@PathVariable String endpointKey){return RuntimeResponse.from(operations.runtime(new ConnectorKey(endpointKey)),clock.instant());}

    @PostMapping("/api/v1/integrations/notifications/endpoints/{endpointKey}/resume")
    RuntimeResponse resume(@PathVariable String endpointKey,@RequestBody(required=false) ReasonRequest body,HttpServletRequest request){return RuntimeResponse.from(operations.resume(new ConnectorKey(endpointKey),actor(request),correlation(request),normalizeReason(body==null?null:body.reason())),clock.instant());}

    private static String normalizeReason(String reason){if(reason==null)return null;String normalized=reason.strip();if(normalized.length()<2||normalized.length()>512)throw new IllegalArgumentException("reason must contain between 2 and 512 characters");return normalized;}
    private static DomainIdentifier actor(HttpServletRequest request){Object value=request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);if(value instanceof DomainIdentifier id)return id;throw new IllegalStateException("authenticated actor is missing");}
    private static DomainIdentifier correlation(HttpServletRequest request){return CorrelationContext.identifier(request).orElseThrow(()->new IllegalStateException("correlation identifier is missing"));}
}
