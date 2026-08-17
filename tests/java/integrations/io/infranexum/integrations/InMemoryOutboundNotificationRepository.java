package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic test repository mirroring the durable outbound outbox/DLQ state machine. */
final class InMemoryOutboundNotificationRepository implements OutboundNotificationRepository {
    private final Map<DomainIdentifier, OutboundNotificationDelivery> deliveries = new LinkedHashMap<>();
    private final Map<String, DomainIdentifier> naturalKeys = new LinkedHashMap<>();
    private final Map<ConnectorKey, OutboundNotificationRuntimeState> states = new LinkedHashMap<>();

    @Override
    public synchronized OutboundNotificationAdmissionOutcome admit(OutboundNotificationAdmission admission) {
        String naturalKey = admission.endpointKey() + "\u0000" + admission.eventId();
        DomainIdentifier existingId = naturalKeys.get(naturalKey);
        if (existingId != null) {
            OutboundNotificationDelivery existing = require(existingId);
            if (!existing.payloadSha256().equals(admission.payloadSha256()) || !existing.eventType().equals(admission.eventType())) {
                throw new DuplicateDeliveryConflictException("notification event identifier was reused with different content");
            }
            return new OutboundNotificationAdmissionOutcome(existing, true);
        }
        OutboundNotificationDelivery delivery = new OutboundNotificationDelivery(
                admission.deliveryId(), admission.endpointKey(), admission.eventId(), admission.eventType(), admission.payload(),
                admission.payloadSha256(), OutboundNotificationStatus.PENDING, 0, admission.createdAt(), admission.createdAt(),
                null, null, null, null, 0, null);
        deliveries.put(delivery.deliveryId(), delivery);
        naturalKeys.put(naturalKey, delivery.deliveryId());
        return new OutboundNotificationAdmissionOutcome(delivery, false);
    }

    @Override
    public synchronized List<OutboundNotificationDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
        List<OutboundNotificationDelivery> claimed = new ArrayList<>();
        deliveries.values().stream()
                .filter(item -> claimable(item, now))
                .filter(item -> !runtimeState(item.endpointKey()).suspendedAt(now))
                .sorted(Comparator.comparing(OutboundNotificationDelivery::availableAt).thenComparing(OutboundNotificationDelivery::deliveryId))
                .limit(limit)
                .forEach(item -> {
                    OutboundNotificationDelivery leased = copy(item, OutboundNotificationStatus.IN_FLIGHT, item.attempts() + 1,
                            item.availableAt(), workerId, now.plus(leaseDuration), null, item.lastFailure(), item.replayCount(), item.lastReplayedAt());
                    deliveries.put(leased.deliveryId(), leased);
                    claimed.add(leased);
                });
        return List.copyOf(claimed);
    }

    @Override
    public synchronized void markDelivered(DomainIdentifier deliveryId, String workerId, Instant deliveredAt) {
        OutboundNotificationDelivery current = requireLease(deliveryId, workerId);
        deliveries.put(deliveryId, copy(current, OutboundNotificationStatus.DELIVERED, current.attempts(), current.availableAt(),
                null, null, deliveredAt, null, current.replayCount(), current.lastReplayedAt()));
        states.put(current.endpointKey(), new OutboundNotificationRuntimeState(current.endpointKey(), 0, null, deliveredAt,
                runtimeState(current.endpointKey()).lastFailureAt()));
    }

    @Override
    public synchronized OutboundNotificationStatus markFailed(
            DomainIdentifier deliveryId, String workerId, Instant failedAt, RetryPolicy retryPolicy, Throwable failure,
            boolean retryable, int suspendAfterDeadLetters, Duration suspensionDuration) {
        OutboundNotificationDelivery current = requireLease(deliveryId, workerId);
        boolean dead = !retryable || current.attempts() >= retryPolicy.maximumAttempts();
        OutboundNotificationStatus next = dead ? OutboundNotificationStatus.DEAD_LETTER : OutboundNotificationStatus.PENDING;
        Instant availableAt = dead ? failedAt : failedAt.plus(retryPolicy.delayAfterFailure(current.attempts()));
        String code = failure instanceof OutboundNotificationTransportException transport ? transport.code() : failure.getClass().getSimpleName();
        deliveries.put(deliveryId, copy(current, next, current.attempts(), availableAt, null, null, null, code,
                current.replayCount(), current.lastReplayedAt()));
        if (dead) {
            OutboundNotificationRuntimeState state = runtimeState(current.endpointKey());
            int failures = state.consecutiveDeadLetters() + 1;
            Instant suspendedUntil = failures >= suspendAfterDeadLetters ? failedAt.plus(suspensionDuration) : state.suspendedUntil();
            states.put(current.endpointKey(), new OutboundNotificationRuntimeState(
                    current.endpointKey(), failures, suspendedUntil, state.lastSuccessAt(), failedAt));
        }
        return next;
    }

    @Override
    public synchronized List<OutboundNotificationDelivery> listDeadLetters(ConnectorKey endpointKey, int offset, int limit) {
        return deliveries.values().stream()
                .filter(item -> item.status() == OutboundNotificationStatus.DEAD_LETTER)
                .filter(item -> endpointKey == null || item.endpointKey().equals(endpointKey))
                .sorted(Comparator.comparing(OutboundNotificationDelivery::createdAt).thenComparing(OutboundNotificationDelivery::deliveryId))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized OutboundNotificationDelivery replay(DomainIdentifier deliveryId, Instant replayedAt) {
        OutboundNotificationDelivery current = require(deliveryId);
        if (current.status() != OutboundNotificationStatus.DEAD_LETTER) {
            throw new OutboundNotificationStateConflictException("only DEAD_LETTER notifications may be replayed");
        }
        OutboundNotificationDelivery replayed = copy(current, OutboundNotificationStatus.PENDING, 0, replayedAt, null, null,
                null, null, current.replayCount() + 1, replayedAt);
        deliveries.put(deliveryId, replayed);
        return replayed;
    }

    @Override
    public synchronized OutboundNotificationRuntimeState runtimeState(ConnectorKey endpointKey) {
        return states.getOrDefault(endpointKey, new OutboundNotificationRuntimeState(endpointKey, 0, null, null, null));
    }

    @Override
    public synchronized OutboundNotificationRuntimeState resume(ConnectorKey endpointKey, Instant resumedAt) {
        OutboundNotificationRuntimeState current = runtimeState(endpointKey);
        OutboundNotificationRuntimeState resumed = new OutboundNotificationRuntimeState(
                endpointKey, 0, null, current.lastSuccessAt(), current.lastFailureAt());
        states.put(endpointKey, resumed);
        return resumed;
    }

    @Override
    public synchronized long backlogSize(ConnectorKey endpointKey, Instant now) {
        return deliveries.values().stream()
                .filter(item -> item.endpointKey().equals(endpointKey))
                .filter(item -> item.status() == OutboundNotificationStatus.PENDING || item.status() == OutboundNotificationStatus.IN_FLIGHT)
                .count();
    }

    @Override
    public synchronized long deadLetterCount(ConnectorKey endpointKey) {
        return deliveries.values().stream()
                .filter(item -> item.endpointKey().equals(endpointKey) && item.status() == OutboundNotificationStatus.DEAD_LETTER)
                .count();
    }

    synchronized OutboundNotificationDelivery require(DomainIdentifier id) {
        OutboundNotificationDelivery value = deliveries.get(id);
        if (value == null) throw new OutboundNotificationNotFoundException("unknown notification");
        return value;
    }

    private OutboundNotificationDelivery requireLease(DomainIdentifier id, String workerId) {
        OutboundNotificationDelivery delivery = require(id);
        if (delivery.status() != OutboundNotificationStatus.IN_FLIGHT || !workerId.equals(delivery.leaseOwner())) {
            throw new IllegalStateException("notification is not leased by this worker");
        }
        return delivery;
    }

    private static boolean claimable(OutboundNotificationDelivery item, Instant now) {
        return (item.status() == OutboundNotificationStatus.PENDING && !item.availableAt().isAfter(now))
                || (item.status() == OutboundNotificationStatus.IN_FLIGHT && !item.leaseUntil().isAfter(now));
    }

    private static OutboundNotificationDelivery copy(
            OutboundNotificationDelivery source, OutboundNotificationStatus status, int attempts, Instant availableAt,
            String leaseOwner, Instant leaseUntil, Instant deliveredAt, String lastFailure, int replayCount, Instant lastReplayedAt) {
        return new OutboundNotificationDelivery(source.deliveryId(), source.endpointKey(), source.eventId(), source.eventType(),
                source.payload(), source.payloadSha256(), status, attempts, source.createdAt(), availableAt, leaseOwner, leaseUntil,
                deliveredAt, lastFailure, replayCount, lastReplayedAt);
    }
}
