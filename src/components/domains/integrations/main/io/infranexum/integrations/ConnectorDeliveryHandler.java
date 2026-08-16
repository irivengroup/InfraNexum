package io.infranexum.integrations;

/** Connector implementation boundary invoked after durable admission. */
public interface ConnectorDeliveryHandler {
    String name();
    void handle(ConnectorDelivery delivery) throws Exception;
}
