package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DomainFailureTest {
    @Test
    void normalizesCodeCopiesDetailsAndExposesStableText() {
        HashMap<String, String> details = new HashMap<>();
        details.put("field", "name");
        DomainErrorCode code = new DomainErrorCode("invalid_name");
        DomainFailure failure = new DomainFailure(code, "Invalid name", details);
        details.clear();
        assertEquals("INVALID_NAME", code.value());
        assertEquals("INVALID_NAME", code.toString());
        assertEquals(Map.of("field", "name"), failure.details());
        assertThrows(UnsupportedOperationException.class, () -> failure.details().put("x", "y"));
    }

    @Test
    void rejectsInvalidCodesAndIncompleteFailures() {
        assertThrows(NullPointerException.class, () -> new DomainErrorCode(null));
        assertThrows(IllegalArgumentException.class, () -> new DomainErrorCode("bad-code"));
        DomainErrorCode code = new DomainErrorCode("ERROR");
        assertThrows(NullPointerException.class, () -> new DomainFailure(null, "message", Map.of()));
        assertThrows(NullPointerException.class, () -> new DomainFailure(code, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DomainFailure(code, " ", Map.of()));
        assertThrows(NullPointerException.class, () -> new DomainFailure(code, "message", null));
    }
}
