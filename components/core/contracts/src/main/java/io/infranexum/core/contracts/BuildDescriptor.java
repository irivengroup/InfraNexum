
package io.infranexum.core.contracts;

import java.util.Objects;

/** Immutable build identity exposed by diagnostic endpoints. */
public record BuildDescriptor(String product, String version, String baseline, ComponentKind component) {
    public BuildDescriptor {
        product = requireText(product, "product");
        version = requireText(version, "version");
        baseline = requireText(baseline, "baseline");
        component = Objects.requireNonNull(component, "component must not be null");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(field + " must not be blank");
        }
        return value.strip();
    }
}
