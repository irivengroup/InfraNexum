package io.infranexum.core.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Exhaustive validation coverage for the durable HTTP idempotency contract. */
final class IdempotencyLedgerEntryTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void normalizesTextAndAcceptsEveryValidOptionalResponseField() {
        IdempotencyLedger.Entry entry = new IdempotencyLedger.Entry(
                " actor ", " createWidget ", " key-12345678 ", HASH,
                IdempotencyLedger.State.COMPLETED, 201, "application/json", "\"v1\"",
                "/api/v1/widgets/1", "e30=", NOW, NOW.plusSeconds(1));
        assertEquals("actor", entry.scopeKey());
        assertEquals("createWidget", entry.operation());
        assertEquals("key-12345678", entry.key());
        assertEquals(HASH, entry.requestSha256());
        assertEquals(201, entry.httpStatus());
        assertEquals(IdempotencyLedger.State.COMPLETED, entry.state());
    }

    @Test
    void acceptsNullHttpStatusForInProgressAndBoundaryStatusCodes() {
        assertNull(entry(null).httpStatus());
        assertEquals(100, entry(100).httpStatus());
        assertEquals(599, entry(599).httpStatus());
    }

    @Test
    void rejectsMissingBlankOrOversizedIdentityFields() {
        assertThrows(NullPointerException.class, () -> raw(null, "op", "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw(" ", "op", "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw("x".repeat(65), "op", "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "x".repeat(161), "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "op", "x".repeat(201), HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
    }

    @Test
    void rejectsInvalidHashStateTimestampsAndHttpStatus() {
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "op", "key", "A".repeat(64), IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "op", "key", "a".repeat(63), IdempotencyLedger.State.IN_PROGRESS, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> raw("actor", "op", "key", HASH, null, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> raw("actor", "op", "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, null, NOW));
        assertThrows(NullPointerException.class, () -> raw("actor", "op", "key", HASH, IdempotencyLedger.State.IN_PROGRESS, null, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "op", "key", HASH, IdempotencyLedger.State.COMPLETED, 99, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> raw("actor", "op", "key", HASH, IdempotencyLedger.State.COMPLETED, 600, NOW, NOW));
    }

    private static IdempotencyLedger.Entry entry(Integer status) {
        return raw("actor", "operation", "key-12345678", HASH, IdempotencyLedger.State.IN_PROGRESS, status, NOW, NOW);
    }

    private static IdempotencyLedger.Entry raw(
            String scope, String operation, String key, String hash, IdempotencyLedger.State state,
            Integer status, Instant createdAt, Instant updatedAt) {
        return new IdempotencyLedger.Entry(
                scope, operation, key, hash, state, status, null, null, null, null, createdAt, updatedAt);
    }
}
