package io.infranexum.server.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.infranexum.core.contracts.CursorPage;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.OffsetPage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HTTP compatibility tests for header-based pagination metadata. */
final class ApiPaginationTest {
    @Test
    void cursorPagePreservesArrayBodyAndAdvertisesContinuation() {
        DomainIdentifier next = DomainIdentifier.parse("018f22b2-7c00-7000-8000-000000000001");
        var response = ApiPagination.cursor(new CursorPage<>(List.of("a", "b"), next), 2);
        assertEquals(List.of("a", "b"), response.getBody());
        assertEquals("2", response.getHeaders().getFirst(ApiPagination.PAGE_LIMIT));
        assertEquals(next.toString(), response.getHeaders().getFirst(ApiPagination.NEXT_CURSOR));
        assertNull(response.getHeaders().getFirst(ApiPagination.NEXT_OFFSET));
    }

    @Test
    void finalCursorPageOmitsContinuation() {
        var response = ApiPagination.cursor(List.of("a"), null, 50);
        assertEquals("50", response.getHeaders().getFirst(ApiPagination.PAGE_LIMIT));
        assertNull(response.getHeaders().getFirst(ApiPagination.NEXT_CURSOR));
    }

    @Test
    void offsetPagePreservesArrayBodyAndAdvertisesContinuation() {
        var response = ApiPagination.offset(new OffsetPage<>(List.of("a"), 25), 25);
        assertEquals(List.of("a"), response.getBody());
        assertEquals("25", response.getHeaders().getFirst(ApiPagination.PAGE_LIMIT));
        assertEquals("25", response.getHeaders().getFirst(ApiPagination.NEXT_OFFSET));
        assertNull(response.getHeaders().getFirst(ApiPagination.NEXT_CURSOR));
    }

    @Test
    void finalOffsetPageOmitsContinuation() {
        var response = ApiPagination.offset(List.of("a"), null, 25);
        assertEquals("25", response.getHeaders().getFirst(ApiPagination.PAGE_LIMIT));
        assertNull(response.getHeaders().getFirst(ApiPagination.NEXT_OFFSET));
    }
}
