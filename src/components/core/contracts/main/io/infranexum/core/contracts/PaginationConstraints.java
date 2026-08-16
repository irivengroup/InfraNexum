package io.infranexum.core.contracts;

/** Shared request bounds that prevent pathological offset-pagination scans. */
public final class PaginationConstraints {
    public static final int MAX_OFFSET = 1_000_000;

    private PaginationConstraints() {}

    /** Validates a zero-based offset used by bounded administration/reference queries. */
    public static int requireOffset(int offset) {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset must be between 0 and " + MAX_OFFSET);
        }
        return offset;
    }
}
