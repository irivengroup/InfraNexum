package io.infranexum.integrations;

/** Durable lifecycle of one external webhook delivery. */
public enum ConnectorDeliveryStatus { PENDING, IN_FLIGHT, PROCESSED, DEAD_LETTER }
