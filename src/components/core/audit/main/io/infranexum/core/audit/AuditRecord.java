package io.infranexum.core.audit;

import java.util.Objects;
import java.util.regex.Pattern;

/** Persisted append-only audit record with a cryptographic link to its predecessor. */
public record AuditRecord(long sequence, AuditEntry entry, String previousHash, String entryHash) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AuditRecord {
        if (sequence < 1) throw new IllegalArgumentException("audit sequence must be positive");
        Objects.requireNonNull(entry, "entry");
        previousHash = digest(previousHash, "previousHash");
        entryHash = digest(entryHash, "entryHash");
    }

    private static String digest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA256.matcher(value).matches()) throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        return value;
    }
}
