package io.infranexum.core.events;

/** Outbound transport boundary; adapters must preserve the envelope unchanged. */
@FunctionalInterface
public interface EventTransport {
    void publish(EventEnvelope event) throws Exception;
}
