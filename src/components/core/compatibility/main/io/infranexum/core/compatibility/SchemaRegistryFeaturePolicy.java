package io.infranexum.core.compatibility;

/** Runtime capability boundary for the Core Schema Registry. */
@FunctionalInterface
public interface SchemaRegistryFeaturePolicy {
    /** Fails closed when the RSOT core capability is unavailable in the active composition. */
    void requireAvailable();
}
