package io.infranexum.identity.access.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable attributes reconstructed by trusted PIP adapters. */
public final class PolicyAttributeBag {
    private final Map<PolicyAttributeSource, Map<String, Set<String>>> values;

    private PolicyAttributeBag(Map<PolicyAttributeSource, Map<String, Set<String>>> values) {
        this.values = values;
    }

    /** Stable representation used only as a cache key; values never leave the PDP boundary. */
    public String fingerprintMaterial() {
        StringBuilder out = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(sourceEntry ->
                sourceEntry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(attributeEntry -> {
                    out.append(sourceEntry.getKey().name()).append(':').append(attributeEntry.getKey()).append('=');
                    attributeEntry.getValue().stream().sorted().forEach(value -> out.append(value.length()).append('#').append(value));
                    out.append(';');
                }));
        return out.toString();
    }

    public Set<String> values(PolicyAttributeSource source, String attribute) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(attribute, "attribute");
        return values.getOrDefault(source, Map.of()).getOrDefault(attribute, Set.of());
    }

    public static Builder builder() { return new Builder(); }

    /** Builder used only by trusted PIP implementations. */
    public static final class Builder {
        private final Map<PolicyAttributeSource, Map<String, Set<String>>> values = new LinkedHashMap<>();

        public Builder put(PolicyAttributeSource source, String attribute, String value) {
            return putAll(source, attribute, ListView.of(value));
        }

        public Builder putAll(PolicyAttributeSource source, String attribute, Collection<String> supplied) {
            Objects.requireNonNull(source, "source");
            String normalized = new PolicyCondition(source, attribute, PolicyOperator.EXISTS, "true").attribute();
            Objects.requireNonNull(supplied, "supplied");
            LinkedHashSet<String> copy = new LinkedHashSet<>();
            for (String value : supplied) copy.add(PolicyCondition.bounded(value, "attribute value", 512));
            values.computeIfAbsent(source, ignored -> new LinkedHashMap<>()).put(normalized, Set.copyOf(copy));
            return this;
        }

        public PolicyAttributeBag build() {
            Map<PolicyAttributeSource, Map<String, Set<String>>> outer = new LinkedHashMap<>();
            values.forEach((source, attributes) -> outer.put(source, Map.copyOf(attributes)));
            return new PolicyAttributeBag(Map.copyOf(outer));
        }
    }

    private static final class ListView {
        private ListView() {}
        static Collection<String> of(String value) { return java.util.List.of(Objects.requireNonNull(value, "value")); }
    }
}
