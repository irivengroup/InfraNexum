package io.infranexum.core.contracts;

import java.util.List;

/** Immutable offset page used by bounded administration/reference collection queries. */
public record OffsetPage<T>(List<T> items, Integer nextOffset) {
    public OffsetPage {
        items = List.copyOf(items);
        if (nextOffset != null && nextOffset < 0) throw new IllegalArgumentException("nextOffset cannot be negative");
    }
}
