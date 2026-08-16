package io.infranexum.server.integrations;

import io.infranexum.integrations.ConnectorInboxDispatcher;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Prevents overlapping local dispatcher iterations while allowing HA nodes to coordinate through JDBC leases. */
final class ConnectorInboxSchedule {
    private final ConnectorInboxDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean();
    ConnectorInboxSchedule(ConnectorInboxDispatcher dispatcher) { this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher"); }

    @Scheduled(fixedDelayString = "${infranexum.integrations.poll-interval:PT1S}")
    void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try { dispatcher.dispatchOnce(); }
        finally { running.set(false); }
    }
}
