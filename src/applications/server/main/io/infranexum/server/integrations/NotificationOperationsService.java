package io.infranexum.server.integrations;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.OutboundNotificationAdmissionOutcome;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationEndpointRegistry;
import io.infranexum.integrations.OutboundNotificationPublisher;
import io.infranexum.integrations.OutboundNotificationRepository;
import io.infranexum.integrations.OutboundNotificationRuntimeObserver;
import io.infranexum.integrations.OutboundNotificationRuntimeState;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Audited application service for notification publication, DLQ and endpoint recovery. */
final class NotificationOperationsService {
    private static final int MAX_PAGE_SIZE = 200;
    private final OutboundNotificationEndpointRegistry endpoints;
    private final OutboundNotificationPublisher publisher;
    private final OutboundNotificationRepository repository;
    private final OutboundNotificationRuntimeObserver observer;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    NotificationOperationsService(
            OutboundNotificationEndpointRegistry endpoints,
            OutboundNotificationPublisher publisher,
            OutboundNotificationRepository repository,
            OutboundNotificationRuntimeObserver observer,
            AuditJournal audit,
            UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock) {
        this.endpoints=Objects.requireNonNull(endpoints,"endpoints");this.publisher=Objects.requireNonNull(publisher,"publisher");
        this.repository=Objects.requireNonNull(repository,"repository");this.observer=Objects.requireNonNull(observer,"observer");
        this.audit=Objects.requireNonNull(audit,"audit");this.ids=Objects.requireNonNull(ids,"ids");this.clock=Objects.requireNonNull(clock,"clock");
    }

    EndpointPage endpoints(int offset,int limit){PaginationConstraints.requireOffset(offset);bounded(limit);List<OutboundNotificationEndpoint> all=endpoints.endpoints().stream().sorted(Comparator.comparing(x->x.endpointKey().value())).toList();int from=Math.min(offset,all.size());int to=Math.min(from+limit,all.size());return new EndpointPage(all.subList(from,to),to<all.size()?to:null);}

    List<OutboundNotificationAdmissionOutcome> publish(String eventId,String eventType,byte[] payload,List<ConnectorKey> endpointKeys,DomainIdentifier actor,DomainIdentifier correlation){requireIdentity(actor,"actor");requireIdentity(correlation,"correlation");var outcomes=publisher.publish(eventId,eventType,payload,endpointKeys);audit(actor,correlation,"integrations.notification.publish","integration_notification",eventId,null,Map.of("event_type",eventType,"endpoint_count",Integer.toString(endpointKeys.size())));return outcomes;}

    DeadLetterPage deadLetters(ConnectorKey key,int offset,int limit){PaginationConstraints.requireOffset(offset);bounded(limit);if(key!=null)endpoints.require(key);var rows=repository.listDeadLetters(key,offset,limit+1);boolean more=rows.size()>limit;var items=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);return new DeadLetterPage(items,more?offset+limit:null);}

    OutboundNotificationDelivery replay(DomainIdentifier deliveryId,DomainIdentifier actor,DomainIdentifier correlation,String reason){requireIdentity(actor,"actor");requireIdentity(correlation,"correlation");var delivery=repository.replay(deliveryId,clock.instant());observer.replayed(delivery.endpointKey());audit(actor,correlation,"integrations.notification.replay","integration_notification_delivery",deliveryId.toString(),reason,Map.of("endpoint",delivery.endpointKey().value(),"replay_count",Integer.toString(delivery.replayCount())));return delivery;}

    RuntimeSnapshot runtime(ConnectorKey key){endpoints.require(key);return new RuntimeSnapshot(repository.runtimeState(key),repository.backlogSize(key,clock.instant()),repository.deadLetterCount(key));}
    RuntimeSnapshot resume(ConnectorKey key,DomainIdentifier actor,DomainIdentifier correlation,String reason){requireIdentity(actor,"actor");requireIdentity(correlation,"correlation");endpoints.require(key);var state=repository.resume(key,clock.instant());audit(actor,correlation,"integrations.notification.resume","integration_notification_endpoint",key.value(),reason,Map.of("endpoint",key.value()));return new RuntimeSnapshot(state,repository.backlogSize(key,clock.instant()),repository.deadLetterCount(key));}

    private void audit(DomainIdentifier actor,DomainIdentifier correlation,String action,String targetType,String targetId,String reason,Map<String,String> metadata){audit.append(new AuditEntry(ids.next(),AuditScope.platform(),actor.toString(),"USER",action,targetType,targetId,"ALLOW",clock.instant(),correlation,"SUCCESS","HTTP",reason,null,null,metadata,"ELEVATED"));}
    private static void requireIdentity(DomainIdentifier value,String name){Objects.requireNonNull(value,name);} private static void bounded(int limit){if(limit<1||limit>MAX_PAGE_SIZE)throw new IllegalArgumentException("limit must be between 1 and 200");}
    record EndpointPage(List<OutboundNotificationEndpoint> items,Integer nextOffset){EndpointPage{items=List.copyOf(items);}}
    record DeadLetterPage(List<OutboundNotificationDelivery> items,Integer nextOffset){DeadLetterPage{items=List.copyOf(items);}}
    record RuntimeSnapshot(OutboundNotificationRuntimeState state,long backlog,long deadLetters){RuntimeSnapshot{Objects.requireNonNull(state,"state");if(backlog<0||deadLetters<0)throw new IllegalArgumentException("runtime counts must be non-negative");}}
}
