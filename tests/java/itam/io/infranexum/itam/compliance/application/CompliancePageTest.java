package io.infranexum.itam.compliance.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies cursor pages snapshot their content instead of exposing mutable caller state. */
class CompliancePageTest {
    @Test
    void pageRequiresItemsAndDefensivelyCopiesCallerCollection() {
        assertThrows(NullPointerException.class, () -> new CompliancePage<String>(null, null));
        ArrayList<String> source = new ArrayList<>(List.of("one"));
        DomainIdentifier cursor = new DomainIdentifier(new UUID(0x0198000000007000L, 0x8000000000000001L));
        CompliancePage<String> page = new CompliancePage<>(source, cursor);
        source.add("two");
        assertEquals(List.of("one"), page.items());
        assertEquals(cursor, page.nextAfterId());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("three"));
    }
}
