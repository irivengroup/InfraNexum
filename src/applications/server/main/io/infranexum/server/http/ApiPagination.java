package io.infranexum.server.http;

import io.infranexum.core.contracts.CursorPage;
import io.infranexum.core.contracts.OffsetPage;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Canonical pagination response headers while legacy list bodies remain wire-compatible. */
public final class ApiPagination {
    public static final String NEXT_CURSOR = "X-Next-Cursor";
    public static final String NEXT_OFFSET = "X-Next-Offset";
    public static final String PAGE_LIMIT = "X-Page-Limit";
    private ApiPagination() {}

    public static <T> ResponseEntity<List<T>> cursor(CursorPage<T> page, int limit) {
        return cursor(page.items(), page.nextCursor() == null ? null : page.nextCursor().toString(), limit);
    }

    public static <T> ResponseEntity<List<T>> cursor(List<T> items, String nextCursor, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PAGE_LIMIT, Integer.toString(limit));
        if (nextCursor != null) headers.set(NEXT_CURSOR, nextCursor);
        return ResponseEntity.ok().headers(headers).body(List.copyOf(items));
    }

    public static <T> ResponseEntity<List<T>> offset(OffsetPage<T> page, int limit) {
        return offset(page.items(), page.nextOffset(), limit);
    }

    public static <T> ResponseEntity<List<T>> offset(List<T> items, Integer nextOffset, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PAGE_LIMIT, Integer.toString(limit));
        if (nextOffset != null) headers.set(NEXT_OFFSET, Integer.toString(nextOffset));
        return ResponseEntity.ok().headers(headers).body(List.copyOf(items));
    }
}
