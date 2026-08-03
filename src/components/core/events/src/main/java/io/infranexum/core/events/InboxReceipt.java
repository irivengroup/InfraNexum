package io.infranexum.core.events;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Durable deduplication receipt for one committed consumer/event pair. */
public record InboxReceipt(
        InboxKey key,
        EventType eventType,
        String payloadSha256,
        Instant receivedAt,
        Instant completedAt) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public InboxReceipt {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        if (!SHA256.matcher(payloadSha256).matches()) {
            throw new IllegalArgumentException("payloadSha256 must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(receivedAt)) {
            throw new IllegalArgumentException("completedAt must not precede receivedAt");
        }
    }
}
