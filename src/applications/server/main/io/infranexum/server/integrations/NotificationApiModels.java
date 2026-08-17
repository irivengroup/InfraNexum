package io.infranexum.server.integrations;

import io.infranexum.integrations.OutboundNotificationAdmissionOutcome;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationRuntimeState;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Secret-free wire models for outbound operational notifications. */
final class NotificationApiModels {
    private NotificationApiModels() {}

    record EndpointResponse(String endpointKey,String transport,String direction,String authority,boolean enabled){
        static EndpointResponse from(OutboundNotificationEndpoint endpoint){return new EndpointResponse(endpoint.endpointKey().value(),"SIGNED_WEBHOOK","OUTBOUND","INFRANEXUM",endpoint.enabled());}
    }
    record PublishRequest(String eventId,String eventType,List<String> endpointKeys,JsonNode payload) {}
    record AdmissionResponse(String deliveryId,String endpointKey,String eventId,String eventType,String status,Instant createdAt){
        static AdmissionResponse from(OutboundNotificationAdmissionOutcome outcome){var d=outcome.delivery();return new AdmissionResponse(d.deliveryId().toString(),d.endpointKey().value(),d.eventId(),d.eventType(),outcome.duplicate()?"DUPLICATE":"ADMITTED",d.createdAt());}
    }
    record DeadLetterResponse(String deliveryId,String endpointKey,String eventId,String eventType,int attempts,String failureCode,int replayCount,Instant createdAt,Instant lastReplayedAt){
        static DeadLetterResponse from(OutboundNotificationDelivery d){return new DeadLetterResponse(d.deliveryId().toString(),d.endpointKey().value(),d.eventId(),d.eventType(),d.attempts(),d.lastFailure(),d.replayCount(),d.createdAt(),d.lastReplayedAt());}
    }
    record RuntimeResponse(String endpointKey,int consecutiveDeadLetters,Instant suspendedUntil,Instant lastSuccessAt,Instant lastFailureAt,boolean suspended,long backlog,long deadLetters){
        static RuntimeResponse from(NotificationOperationsService.RuntimeSnapshot snapshot,Instant now){OutboundNotificationRuntimeState s=snapshot.state();return new RuntimeResponse(s.endpointKey().value(),s.consecutiveDeadLetters(),s.suspendedUntil(),s.lastSuccessAt(),s.lastFailureAt(),s.suspendedAt(now),snapshot.backlog(),snapshot.deadLetters());}
    }
    record ReasonRequest(String reason) {}
}
