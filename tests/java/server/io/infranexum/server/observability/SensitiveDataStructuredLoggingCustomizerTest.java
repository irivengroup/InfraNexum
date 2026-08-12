package io.infranexum.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

class SensitiveDataStructuredLoggingCustomizerTest {
    @SuppressWarnings("unchecked")
    @Test
    void installsValueProcessorThatSanitizesEveryStructuredString() {
        JsonWriter.Members<Object> members = mock(JsonWriter.Members.class);
        AtomicReference<JsonWriter.ValueProcessor<?>> processor = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
                    processor.set(invocation.getArgument(0));
                    return null;
                })
                .when(members)
                .applyingValueProcessor(any());

        new SensitiveDataStructuredLoggingCustomizer().customize(members);

        verify(members).applyingValueProcessor(any());
        @SuppressWarnings("unchecked")
        JsonWriter.ValueProcessor<Object> valueProcessor =
                (JsonWriter.ValueProcessor<Object>) processor.get();
        Object value = valueProcessor.processValue(JsonWriter.MemberPath.of("message"), "password=top-secret");
        assertEquals("password=[REDACTED]", value);
    }
}
