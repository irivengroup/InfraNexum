package io.infranexum.core.audit;

/** Shared deterministic JSON string escaping for audit canonical forms. */
enum AuditJsonStrings {
    ;

    static void quote(StringBuilder output, String value) {
        java.util.Objects.requireNonNull(output, "output");
        java.util.Objects.requireNonNull(value, "value");
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
                    if (character < 0x20) output.append("\\u%04x".formatted((int) character));
                    else output.append(character);
                }
            }
        }
        output.append('"');
    }
}
