package io.infranexum.core.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Processes inbound events transactionally and deduplicates committed deliveries. */
public final class InboxProcessor {
    private final TransactionalEventStore store;
    private final Clock clock;

    public InboxProcessor(TransactionalEventStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the handler once per consumer/event pair.
     *
     * <p>A handler failure rolls back the inbox reservation and any newly staged
     * outbox events, allowing a later transport redelivery to retry safely.
     */
    public TransactionOutcome<InboxProcessingResult> process(
            String consumerName, EventEnvelope event, InboxHandler handler) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        InboxKey key = new InboxKey(consumerName, event.eventId());
        Instant receivedAt = clock.instant();
        InboxReservation reservation = new InboxReservation(
                key, event.eventType(), sha256(event.payload()), receivedAt);
        return store.execute(transaction -> {
            if (transaction.beginInbox(reservation) == InboxDecision.DUPLICATE) {
                return InboxProcessingResult.DUPLICATE;
            }
            handler.handle(event, transaction);
            transaction.completeInbox(key, clock.instant());
            return InboxProcessingResult.PROCESSED;
        });
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
