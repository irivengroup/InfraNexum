package io.infranexum.integrations;

/** Technical transport used by the dispatcher after durable outbox admission. */
public interface OutboundNotificationTransport {
    void deliver(OutboundNotificationEndpoint endpoint, OutboundNotificationDelivery delivery);
}
