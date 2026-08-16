package io.infranexum.core.contracts;

import java.util.List;

/** Immutable cursor page used by mutable/high-volume collection queries. */
public record CursorPage<T>(List<T> items, DomainIdentifier nextCursor) {
    public CursorPage {
        items = List.copyOf(items);
    }
}
