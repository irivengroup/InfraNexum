package io.infranexum.core.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Minimal canonical JSON codec for the flat string metadata contract. */
public final class AuditMetadataJson {
    private AuditMetadataJson() {}

    /** Encodes metadata as a deterministic JSON object. */
    public static String encode(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> item : new TreeMap<>(metadata).entrySet()) {
            if (!first) out.append(',');
            first = false;
            AuditJsonStrings.quote(out, item.getKey());
            out.append(':');
            AuditJsonStrings.quote(out, item.getValue());
        }
        return out.append('}').toString();
    }

    /** Decodes the exact flat object grammar produced by {@link #encode(Map)}. */
    public static Map<String, String> decode(String json) {
        Objects.requireNonNull(json, "json");
        Cursor cursor = new Cursor(json);
        cursor.skipWhitespace();
        cursor.expect('{');
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        cursor.skipWhitespace();
        if (cursor.consume('}')) return Map.of();
        while (true) {
            String key = cursor.string();
            cursor.skipWhitespace();
            cursor.expect(':');
            String value = cursor.string();
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate audit metadata key");
            cursor.skipWhitespace();
            if (cursor.consume('}')) break;
            cursor.expect(',');
        }
        cursor.skipWhitespace();
        if (!cursor.atEnd()) throw new IllegalArgumentException("trailing audit metadata content");
        return Map.copyOf(values);
    }

    private static final class Cursor {
        private final String value;
        private int index;

        private Cursor(String value) { this.value = value; }
        private boolean atEnd() { return index == value.length(); }
        private void skipWhitespace() { while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++; }
        private boolean consume(char expected) {
            skipWhitespace();
            if (index < value.length() && value.charAt(index) == expected) { index++; return true; }
            return false;
        }
        private void expect(char expected) {
            if (!consume(expected)) throw new IllegalArgumentException("invalid audit metadata JSON at offset " + index);
        }
        private String string() {
            skipWhitespace();
            if (index >= value.length() || value.charAt(index++) != '"') throw new IllegalArgumentException("expected audit metadata string");
            StringBuilder out = new StringBuilder();
            while (index < value.length()) {
                char ch = value.charAt(index++);
                if (ch == '"') return out.toString();
                if (ch != '\\') { if (ch < 0x20) throw new IllegalArgumentException("control character in audit metadata JSON"); out.append(ch); continue; }
                if (index >= value.length()) throw new IllegalArgumentException("truncated audit metadata escape");
                char escaped = value.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw new IllegalArgumentException("invalid audit metadata escape");
                }
            }
            throw new IllegalArgumentException("unterminated audit metadata string");
        }
        private char unicode() {
            if (index + 4 > value.length()) throw new IllegalArgumentException("truncated audit metadata unicode escape");
            int code = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(value.charAt(index++), 16);
                if (digit < 0) throw new IllegalArgumentException("invalid audit metadata unicode escape");
                code = (code << 4) | digit;
            }
            return (char) code;
        }
    }
}
