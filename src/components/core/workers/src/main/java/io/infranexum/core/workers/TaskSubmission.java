package io.infranexum.core.workers;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable request used to schedule a one-shot background task. */
public record TaskSubmission(
        TaskType type,
        String idempotencyKey,
        Map<String, String> parameters,
        Instant notBefore) {
    private static final int MAX_IDEMPOTENCY_KEY = 256;
    private static final int MAX_PARAMETER_COUNT = 64;
    private static final int MAX_PARAMETER_VALUE = 4_096;
    private static final int MAX_TOTAL_PARAMETER_CHARS = 32_768;
    private static final Pattern PARAMETER_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    public TaskSubmission {
        Objects.requireNonNull(type, "type");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY);
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(notBefore, "notBefore");
        if (parameters.size() > MAX_PARAMETER_COUNT) {
            throw new IllegalArgumentException("parameters must contain at most " + MAX_PARAMETER_COUNT + " entries");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        int total = 0;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "parameter key").strip();
            if (!PARAMETER_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("invalid task parameter key: " + key);
            }
            String value = Objects.requireNonNull(entry.getValue(), "parameter value");
            if (value.length() > MAX_PARAMETER_VALUE) {
                throw new IllegalArgumentException("task parameter value is too large: " + key);
            }
            total = Math.addExact(total, key.length() + value.length());
            if (total > MAX_TOTAL_PARAMETER_CHARS) {
                throw new IllegalArgumentException("task parameters exceed the aggregate size limit");
            }
            if (sorted.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate task parameter key after normalization: " + key);
            }
        }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
        }
        return normalized;
    }
}
