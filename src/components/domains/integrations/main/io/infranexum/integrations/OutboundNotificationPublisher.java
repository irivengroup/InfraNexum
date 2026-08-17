package io.infranexum.integrations;

import io.infranexum.core.contracts.UuidV7Generator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates, fans out and durably admits one operational event to explicit endpoints. */
public final class OutboundNotificationPublisher {
    private static final int MAX_ENDPOINTS_PER_EVENT = 64;
    private static final String EVENT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{7,199}";
    private static final String EVENT_TYPE_PATTERN = "[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,15}";

    private final OutboundNotificationEndpointRegistry endpoints;
    private final OutboundNotificationRepository repository;
    private final OutboundNotificationRuntimeObserver observer;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final int maximumPayloadBytes;

    public OutboundNotificationPublisher(
            OutboundNotificationEndpointRegistry endpoints,
            OutboundNotificationRepository repository,
            OutboundNotificationRuntimeObserver observer,
            UuidV7Generator ids,
            Clock clock,
            int maximumPayloadBytes) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumPayloadBytes < 1 || maximumPayloadBytes > 1_048_576) throw new IllegalArgumentException("maximumPayloadBytes must be between 1 and 1048576");
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public List<OutboundNotificationAdmissionOutcome> publish(
            String eventId, String eventType, byte[] payload, Collection<ConnectorKey> endpointKeys) {
        String normalizedEventId = requirePattern(eventId, EVENT_ID_PATTERN, "eventId");
        String normalizedEventType = requirePattern(eventType, EVENT_TYPE_PATTERN, "eventType");
        byte[] body = Objects.requireNonNull(payload, "payload").clone();
        if (body.length == 0 || body.length > maximumPayloadBytes) throw new IllegalArgumentException("notification payload size is invalid");
        Objects.requireNonNull(endpointKeys, "endpointKeys");
        if (endpointKeys.isEmpty() || endpointKeys.size() > MAX_ENDPOINTS_PER_EVENT) throw new IllegalArgumentException("notification endpoint count must be between 1 and 64");
        Set<ConnectorKey> unique = new HashSet<>(endpointKeys);
        if (unique.size() != endpointKeys.size()) throw new IllegalArgumentException("notification endpoint keys must be unique");

        String digest = sha256(body);
        var now = clock.instant();
        List<OutboundNotificationAdmissionOutcome> outcomes = new ArrayList<>(unique.size());
        for (ConnectorKey key : endpointKeys) {
            OutboundNotificationEndpoint endpoint = endpoints.require(key);
            if (!endpoint.enabled()) throw new IllegalArgumentException("notification endpoint is disabled: " + key.value());
            var admission = new OutboundNotificationAdmission(ids.next(), key, normalizedEventId, normalizedEventType, body, digest, now);
            var outcome = repository.admit(admission);
            observer.admitted(key, outcome.duplicate());
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private static String requirePattern(String value, String pattern, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (!normalized.matches(pattern)) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }

    private static String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
