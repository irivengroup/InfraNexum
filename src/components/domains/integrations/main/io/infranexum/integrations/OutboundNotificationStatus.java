package io.infranexum.integrations;

/** Durable lifecycle of an outbound operational notification. */
public enum OutboundNotificationStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    DEAD_LETTER
}
