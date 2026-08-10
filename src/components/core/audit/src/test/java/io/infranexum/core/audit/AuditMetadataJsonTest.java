package io.infranexum.core.audit;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AuditMetadataJsonTest {
    @Test void roundTripsCanonicalEscapedMetadata() {
        Map<String,String> value = Map.of("b", "line\nquote\"", "a", "slash\\tab\t");
        String json = AuditMetadataJson.encode(value);
        assertEquals("{\"a\":\"slash\\\\tab\\t\",\"b\":\"line\\nquote\\\"\"}", json);
        assertEquals(value, AuditMetadataJson.decode(json));
        assertEquals(Map.of(), AuditMetadataJson.decode(" { } "));
        assertEquals(Map.of("x", "A"), AuditMetadataJson.decode("{\"x\":\"\\u0041\"}"));
    }

    @Test void rejectsMalformedMetadata() {
        assertThrows(NullPointerException.class, () -> AuditMetadataJson.encode(null));
        assertThrows(NullPointerException.class, () -> AuditMetadataJson.decode(null));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("[]"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"1\",\"a\":\"2\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"1\"} trailing"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\" \"1\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":true}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"\\q\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"\\u12xz\"}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"unterminated}"));
        assertThrows(IllegalArgumentException.class, () -> AuditMetadataJson.decode("{\"a\":\"\\"));
    }
}
