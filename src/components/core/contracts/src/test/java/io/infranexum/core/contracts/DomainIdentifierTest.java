package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DomainIdentifierTest {
    @Test
    void parsesOrdersAndExposesUuidV7Timestamp() {
        DomainIdentifier first = DomainIdentifier.parse("018f22b2-7c00-7000-8000-000000000001");
        DomainIdentifier second = DomainIdentifier.parse("018f22b2-7c00-7000-8000-000000000002");
        assertEquals(1713423903744L, first.unixEpochMillis());
        assertEquals(first, DomainIdentifier.parse(first.toString()));
        assertTrue(first.compareTo(second) < 0);
        assertThrows(NullPointerException.class, () -> first.compareTo(null));
    }

    @Test
    void rejectsNullMalformedNonV7AndWrongVariantIdentifiers() {
        assertThrows(NullPointerException.class, () -> new DomainIdentifier(null));
        assertThrows(NullPointerException.class, () -> DomainIdentifier.parse(null));
        assertThrows(IllegalArgumentException.class, () -> DomainIdentifier.parse("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> new DomainIdentifier(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> DomainIdentifier.parse("018f22b2-7c00-7000-0000-000000000001"));
    }
}
