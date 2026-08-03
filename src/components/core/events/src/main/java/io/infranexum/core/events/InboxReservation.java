package io.infranexum.core.events;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable metadata reserved before an inbound handler starts its transaction. */
public record InboxReservation(
        InboxKey key,
        EventType eventType,
        String payloadSha256,
        Instant receivedAt) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public InboxReservation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        if (!SHA256.matcher(payloadSha256).matches()) {
            throw new IllegalArgumentException("payloadSha256 must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
