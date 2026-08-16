package io.infranexum.integrations;

/** Resolves the certified handler selected by an endpoint definition. */
@FunctionalInterface
public interface ConnectorHandlerRegistry {
    ConnectorDeliveryHandler require(String handlerName);
}
