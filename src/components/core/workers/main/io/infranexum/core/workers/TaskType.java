package io.infranexum.core.workers;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable functional identifier for a registered background task handler. */
public record TaskType(String value) implements Comparable<TaskType> {
    private static final int MAX_LENGTH = 160;
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public TaskType {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty() || value.length() > MAX_LENGTH || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "task type must be 1-160 lowercase alphanumeric characters separated by '.' or '-'");
        }
    }

    @Override
    public int compareTo(TaskType other) {
        Objects.requireNonNull(other, "other");
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
