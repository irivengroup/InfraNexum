package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioral coverage for the shared PGM-05-E01 pagination contracts. */
final class PaginationContractsTest {
    @Test
    void cursorPageCopiesItemsAndCarriesOptionalCursor() {
        List<String> mutable = new ArrayList<>(List.of("a", "b"));
        DomainIdentifier cursor = DomainIdentifier.parse("018f22b2-7c00-7000-8000-000000000001");
        CursorPage<String> page = new CursorPage<>(mutable, cursor);
        mutable.clear();
        assertEquals(List.of("a", "b"), page.items());
        assertEquals(cursor, page.nextCursor());
    }

    @Test
    void offsetPageCopiesItemsAndRejectsNegativeContinuation() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        OffsetPage<String> page = new OffsetPage<>(mutable, 25);
        mutable.clear();
        assertEquals(List.of("a"), page.items());
        assertEquals(25, page.nextOffset());
        assertEquals(null, new OffsetPage<>(List.of(), null).nextOffset());
        assertThrows(IllegalArgumentException.class, () -> new OffsetPage<>(List.of(), -1));
    }

    @Test
    void offsetConstraintAcceptsBoundaryAndRejectsPathologicalValues() {
        assertEquals(0, PaginationConstraints.requireOffset(0));
        assertEquals(PaginationConstraints.MAX_OFFSET, PaginationConstraints.requireOffset(PaginationConstraints.MAX_OFFSET));
        assertThrows(IllegalArgumentException.class, () -> PaginationConstraints.requireOffset(-1));
        assertThrows(IllegalArgumentException.class, () -> PaginationConstraints.requireOffset(PaginationConstraints.MAX_OFFSET + 1));
    }
}
