package io.infranexum.server.integrations;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.ConnectorDelivery;
import io.infranexum.integrations.ConnectorInboxRepository;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRuntimeObserver;
import io.infranexum.integrations.ConnectorRuntimeState;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;

/** Audited operator service for connector DLQ and suspension controls. */
final class IntegrationOperationsService {
    private static final int MAX_PAGE_SIZE = 200;

    private final ConnectorInboxRepository inbox;
    private final AuditJournal audit;
    private final ConnectorRuntimeObserver observer;
    private final UuidV7Generator ids;
    private final Clock clock;

    IntegrationOperationsService(
            ConnectorInboxRepository inbox,
            AuditJournal audit,
            ConnectorRuntimeObserver observer,
            UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    DeadLetterPage deadLetters(ConnectorKey key, int offset, int limit) {
        PaginationConstraints.requireOffset(offset);
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        List<ConnectorDelivery> rows = inbox.listDeadLetters(key, offset, limit + 1);
        boolean more = rows.size() > limit;
        List<ConnectorDelivery> items = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new DeadLetterPage(items, more ? offset + limit : null);
    }

    RuntimeSnapshot runtime(ConnectorKey key) {
        Objects.requireNonNull(key, "key");
        return new RuntimeSnapshot(
                inbox.runtimeState(key),
                inbox.backlogSize(key, clock.instant()),
                inbox.deadLetterCount(key));
    }

    ConnectorDelivery replay(
            DomainIdentifier deliveryId,
            DomainIdentifier actor,
            DomainIdentifier correlation,
            String reason) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        requireIdentity(actor, "actor");
        requireIdentity(correlation, "correlation");
        ConnectorDelivery delivery = inbox.replay(deliveryId, clock.instant());
        observer.replayed(delivery.connectorKey());
        audit(
                actor,
                correlation,
                "integrations.dlq.replay",
                "integration_delivery",
                deliveryId.toString(),
                reason,
                Map.of(
                        "connector", delivery.connectorKey().value(),
                        "replay_count", Integer.toString(delivery.replayCount())));
        return delivery;
    }

    RuntimeSnapshot resume(
            ConnectorKey key,
            DomainIdentifier actor,
            DomainIdentifier correlation,
            String reason) {
        Objects.requireNonNull(key, "key");
        requireIdentity(actor, "actor");
        requireIdentity(correlation, "correlation");
        ConnectorRuntimeState state = inbox.resume(key, clock.instant());
        audit(
                actor,
                correlation,
                "integrations.connector.resume",
                "integration_connector",
                key.value(),
                reason,
                Map.of("connector", key.value()));
        return new RuntimeSnapshot(state, inbox.backlogSize(key, clock.instant()), inbox.deadLetterCount(key));
    }

    private static void requireIdentity(DomainIdentifier value, String name) {
        Objects.requireNonNull(value, name);
    }

    private void audit(
            DomainIdentifier actor,
            DomainIdentifier correlation,
            String action,
            String targetType,
            String targetId,
            String reason,
            Map<String, String> metadata) {
        audit.append(new AuditEntry(
                ids.next(),
                AuditScope.platform(),
                actor.toString(),
                "USER",
                action,
                targetType,
                targetId,
                "ALLOW",
                clock.instant(),
                correlation,
                "SUCCESS",
                "HTTP",
                reason,
                null,
                null,
                metadata,
                "ELEVATED"));
    }

    record DeadLetterPage(List<ConnectorDelivery> items, Integer nextOffset) {
        DeadLetterPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    record RuntimeSnapshot(ConnectorRuntimeState state, long backlog, long deadLetters) {
        RuntimeSnapshot {
            Objects.requireNonNull(state, "state");
            if (backlog < 0 || deadLetters < 0) throw new IllegalArgumentException("runtime counts must be non-negative");
        }
    }
}
