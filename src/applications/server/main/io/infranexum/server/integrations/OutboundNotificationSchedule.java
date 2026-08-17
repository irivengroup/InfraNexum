package io.infranexum.server.integrations;

import io.infranexum.integrations.OutboundNotificationDispatcher;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Prevents local overlap while JDBC leases coordinate notification dispatch across Server nodes. */
final class OutboundNotificationSchedule {
    private final OutboundNotificationDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean();
    OutboundNotificationSchedule(OutboundNotificationDispatcher dispatcher) { this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher"); }

    @Scheduled(fixedDelayString = "${infranexum.integrations.poll-interval:PT1S}")
    void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try { dispatcher.dispatchAvailable(); }
        finally { running.set(false); }
    }
}
