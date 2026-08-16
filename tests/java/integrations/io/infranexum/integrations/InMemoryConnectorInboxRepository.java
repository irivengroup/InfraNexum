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

/** Deterministic repository test double that mirrors the durable inbox state machine. */
final class InMemoryConnectorInboxRepository implements ConnectorInboxRepository {
    private final Map<DomainIdentifier, ConnectorDelivery> deliveries = new LinkedHashMap<>();
    private final Map<String, DomainIdentifier> externalIds = new LinkedHashMap<>();
    private final Map<ConnectorKey, ConnectorRuntimeState> states = new LinkedHashMap<>();

    @Override
    public synchronized WebhookAdmissionOutcome admit(WebhookAdmission admission) {
        String externalKey = admission.connectorKey() + "\u0000" + admission.externalDeliveryId();
        DomainIdentifier existingId = externalIds.get(externalKey);
        if (existingId != null) {
            ConnectorDelivery existing = deliveries.get(existingId);
            if (!existing.payloadSha256().equals(admission.payloadSha256())) {
                throw new DuplicateDeliveryConflictException("provider delivery identifier was reused with different content");
            }
            return new WebhookAdmissionOutcome(existing, true);
        }
        ConnectorDelivery delivery = new ConnectorDelivery(
                admission.deliveryId(), admission.connectorKey(), admission.externalDeliveryId(), admission.payload(),
                admission.payloadSha256(), ConnectorDeliveryStatus.PENDING, 0, admission.receivedAt(), admission.receivedAt(),
                null, null, null, null, 0, null);
        deliveries.put(delivery.deliveryId(), delivery);
        externalIds.put(externalKey, delivery.deliveryId());
        return new WebhookAdmissionOutcome(delivery, false);
    }

    @Override
    public synchronized List<ConnectorDelivery> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
        List<ConnectorDelivery> claimed = new ArrayList<>();
        deliveries.values().stream()
                .filter(item -> claimable(item, now))
                .filter(item -> !runtimeState(item.connectorKey()).suspendedAt(now))
                .sorted(Comparator.comparing(ConnectorDelivery::availableAt).thenComparing(ConnectorDelivery::deliveryId))
                .limit(limit)
                .forEach(item -> {
                    ConnectorDelivery leased = copy(item, ConnectorDeliveryStatus.IN_FLIGHT, item.attempts() + 1,
                            item.availableAt(), workerId, now.plus(leaseDuration), null, item.lastFailure(),
                            item.replayCount(), item.lastReplayedAt());
                    deliveries.put(leased.deliveryId(), leased);
                    claimed.add(leased);
                });
        return List.copyOf(claimed);
    }

    @Override
    public synchronized void markProcessed(DomainIdentifier deliveryId, String workerId, Instant processedAt) {
        ConnectorDelivery current = requireLease(deliveryId, workerId);
        deliveries.put(deliveryId, copy(current, ConnectorDeliveryStatus.PROCESSED, current.attempts(), current.availableAt(),
                null, null, processedAt, null, current.replayCount(), current.lastReplayedAt()));
        states.put(current.connectorKey(), new ConnectorRuntimeState(current.connectorKey(), 0, null, processedAt, null));
    }

    @Override
    public synchronized ConnectorDeliveryStatus markFailed(
            DomainIdentifier deliveryId, String workerId, Instant failedAt, RetryPolicy retryPolicy, Throwable failure,
            int suspendAfterDeadLetters, Duration suspensionDuration) {
        ConnectorDelivery current = requireLease(deliveryId, workerId);
        boolean dead = current.attempts() >= retryPolicy.maximumAttempts();
        ConnectorDeliveryStatus next = dead ? ConnectorDeliveryStatus.DEAD_LETTER : ConnectorDeliveryStatus.PENDING;
        Instant availableAt = dead ? failedAt : failedAt.plus(retryPolicy.delayAfterFailure(current.attempts()));
        deliveries.put(deliveryId, copy(current, next, current.attempts(), availableAt, null, null, null,
                failure.getClass().getName(), current.replayCount(), current.lastReplayedAt()));
        if (dead) {
            ConnectorRuntimeState state = runtimeState(current.connectorKey());
            int failures = state.consecutiveDeadLetters() + 1;
            Instant suspendedUntil = failures >= suspendAfterDeadLetters ? failedAt.plus(suspensionDuration) : state.suspendedUntil();
            states.put(current.connectorKey(), new ConnectorRuntimeState(
                    current.connectorKey(), failures, suspendedUntil, state.lastSuccessAt(), failedAt));
        }
        return next;
    }

    @Override
    public synchronized List<ConnectorDelivery> listDeadLetters(ConnectorKey connectorKey, int offset, int limit) {
        return deliveries.values().stream()
                .filter(item -> item.status() == ConnectorDeliveryStatus.DEAD_LETTER)
                .filter(item -> connectorKey == null || item.connectorKey().equals(connectorKey))
                .sorted(Comparator.comparing(ConnectorDelivery::receivedAt).thenComparing(ConnectorDelivery::deliveryId))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized ConnectorDelivery replay(DomainIdentifier deliveryId, Instant replayedAt) {
        ConnectorDelivery current = require(deliveryId);
        if (current.status() != ConnectorDeliveryStatus.DEAD_LETTER) {
            throw new ConnectorDeliveryStateConflictException("only DEAD_LETTER deliveries may be replayed");
        }
        ConnectorDelivery replayed = copy(current, ConnectorDeliveryStatus.PENDING, 0, replayedAt, null, null, null,
                null, current.replayCount() + 1, replayedAt);
        deliveries.put(deliveryId, replayed);
        return replayed;
    }

    @Override
    public synchronized ConnectorRuntimeState runtimeState(ConnectorKey connectorKey) {
        return states.getOrDefault(connectorKey, new ConnectorRuntimeState(connectorKey, 0, null, null, null));
    }

    @Override
    public synchronized ConnectorRuntimeState resume(ConnectorKey connectorKey, Instant resumedAt) {
        ConnectorRuntimeState current = runtimeState(connectorKey);
        ConnectorRuntimeState resumed = new ConnectorRuntimeState(
                connectorKey, 0, null, current.lastSuccessAt(), current.lastFailureAt());
        states.put(connectorKey, resumed);
        return resumed;
    }

    @Override
    public synchronized long backlogSize(ConnectorKey connectorKey, Instant now) {
        return deliveries.values().stream()
                .filter(item -> item.connectorKey().equals(connectorKey))
                .filter(item -> item.status() == ConnectorDeliveryStatus.PENDING || item.status() == ConnectorDeliveryStatus.IN_FLIGHT)
                .count();
    }

    @Override
    public synchronized long deadLetterCount(ConnectorKey connectorKey) {
        return deliveries.values().stream()
                .filter(item -> item.connectorKey().equals(connectorKey) && item.status() == ConnectorDeliveryStatus.DEAD_LETTER)
                .count();
    }

    synchronized ConnectorDelivery require(DomainIdentifier id) {
        ConnectorDelivery delivery = deliveries.get(id);
        if (delivery == null) throw new ConnectorDeliveryNotFoundException("unknown delivery");
        return delivery;
    }

    private ConnectorDelivery requireLease(DomainIdentifier id, String workerId) {
        ConnectorDelivery delivery = require(id);
        if (delivery.status() != ConnectorDeliveryStatus.IN_FLIGHT || !workerId.equals(delivery.leaseOwner())) {
            throw new IllegalStateException("delivery is not leased by this worker");
        }
        return delivery;
    }

    private static boolean claimable(ConnectorDelivery item, Instant now) {
        return (item.status() == ConnectorDeliveryStatus.PENDING && !item.availableAt().isAfter(now))
                || (item.status() == ConnectorDeliveryStatus.IN_FLIGHT && !item.leaseUntil().isAfter(now));
    }

    private static ConnectorDelivery copy(
            ConnectorDelivery source, ConnectorDeliveryStatus status, int attempts, Instant availableAt,
            String leaseOwner, Instant leaseUntil, Instant processedAt, String lastFailure,
            int replayCount, Instant lastReplayedAt) {
        return new ConnectorDelivery(source.deliveryId(), source.connectorKey(), source.externalDeliveryId(), source.payload(),
                source.payloadSha256(), status, attempts, source.receivedAt(), availableAt, leaseOwner, leaseUntil,
                processedAt, lastFailure, replayCount, lastReplayedAt);
    }
}
