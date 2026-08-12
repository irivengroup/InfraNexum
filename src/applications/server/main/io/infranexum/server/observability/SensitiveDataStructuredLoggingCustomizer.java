package io.infranexum.server.observability;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Applies the InfraNexum secret-redaction policy immediately before structured JSON serialization. */
public final class SensitiveDataStructuredLoggingCustomizer
        implements StructuredLoggingJsonMembersCustomizer<Object> {
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(new JsonWriter.ValueProcessor<Object>() {
            @Override
            public Object processValue(JsonWriter.MemberPath path, Object value) {
                if (value instanceof String text) {
                    return redactor.redact(path.toUnescapedString(), text);
                }
                return value;
            }
        });
    }
}
