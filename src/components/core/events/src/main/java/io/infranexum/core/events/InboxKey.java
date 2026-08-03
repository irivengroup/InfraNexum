package io.infranexum.core.events;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;
import java.util.regex.Pattern;

/** Composite deduplication key for one consumer and one event. */
public record InboxKey(String consumerName, DomainIdentifier eventId) implements Comparable<InboxKey> {
    private static final Pattern CONSUMER = Pattern.compile("[a-z][a-z0-9.-]{2,159}");

    public InboxKey {
        Objects.requireNonNull(consumerName, "consumerName");
        consumerName = consumerName.strip();
        if (!CONSUMER.matcher(consumerName).matches()) {
            throw new IllegalArgumentException("invalid consumer name: " + consumerName);
        }
        Objects.requireNonNull(eventId, "eventId");
    }

    @Override
    public int compareTo(InboxKey other) {
        Objects.requireNonNull(other, "other");
        int byConsumer = consumerName.compareTo(other.consumerName);
        return byConsumer != 0 ? byConsumer : eventId.compareTo(other.eventId);
    }
}
