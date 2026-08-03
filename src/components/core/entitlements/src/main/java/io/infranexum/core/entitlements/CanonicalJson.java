package io.infranexum.core.entitlements;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic UTF-8 JSON canonicalizer used by activation signatures and integrity proofs. */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static byte[] bytes(Object value) {
        return string(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String string(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            appendString(output, text);
        } else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger) {
            output.append(value);
        } else if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) {
            throw new IllegalArgumentException("floating-point values are forbidden in canonical activation JSON");
        } else if (value instanceof Map<?, ?> map) {
            appendMap(output, map);
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(output, iterable);
        } else {
            throw new IllegalArgumentException("unsupported canonical JSON value: " + value.getClass().getName());
        }
    }

    private static void appendMap(StringBuilder output, Map<?, ?> source) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("canonical JSON object keys must be strings");
            }
            if (sorted.putIfAbsent(text, value) != null) {
                throw new IllegalArgumentException("duplicate canonical JSON key: " + text);
            }
        });
        output.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            appendString(output, entry.getKey());
            output.append(':');
            append(output, entry.getValue());
        }
        output.append('}');
    }

    private static void appendIterable(StringBuilder output, Iterable<?> source) {
        List<Object> values = new ArrayList<>();
        source.forEach(values::add);
        output.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            append(output, values.get(index));
        }
        output.append(']');
    }

    private static void appendString(StringBuilder output, String value) {
        Objects.requireNonNull(value, "value");
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
