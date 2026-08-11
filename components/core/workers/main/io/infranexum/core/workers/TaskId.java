package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Type-safe UUIDv7 identifier for one scheduled task. */
public record TaskId(DomainIdentifier value) implements Comparable<TaskId> {
    public TaskId {
        Objects.requireNonNull(value, "value");
    }

    public static TaskId parse(String value) {
        return new TaskId(DomainIdentifier.parse(value));
    }

    @Override
    public int compareTo(TaskId other) {
        Objects.requireNonNull(other, "other");
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
