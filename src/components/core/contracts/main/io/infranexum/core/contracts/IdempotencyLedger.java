package io.infranexum.core.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable ledger for HTTP idempotency reservations and deterministic successful-response replay.
 *
 * <p>The ledger is deliberately transport-neutral. API adapters scope keys by authenticated actor and
 * operation. An IN_PROGRESS record is never automatically expired because it can represent an
 * indeterminate process crash after a business commit; retrying it automatically could duplicate a
 * mutation.
 */
public interface IdempotencyLedger {
    enum State { IN_PROGRESS, COMPLETED, INDETERMINATE }

    record Entry(
            String scopeKey,
            String operation,
            String key,
            String requestSha256,
            State state,
            Integer httpStatus,
            String contentType,
            String etag,
            String location,
            String responseBodyBase64,
            Instant createdAt,
            Instant updatedAt) {
        public Entry {
            scopeKey = require(scopeKey, "scopeKey", 64);
            operation = require(operation, "operation", 160);
            key = require(key, "key", 200);
            requestSha256 = requireHash(requestSha256);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
                throw new IllegalArgumentException("httpStatus must be a valid HTTP status");
            }
        }

        private static String require(String value, String name, int max) {
            String normalized = Objects.requireNonNull(value, name).strip();
            if (normalized.isEmpty() || normalized.length() > max) {
                throw new IllegalArgumentException(name + " must contain 1.." + max + " characters");
            }
            return normalized;
        }

        private static String requireHash(String value) {
            String normalized = require(value, "requestSha256", 64);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestSha256 must be lowercase SHA-256 hex");
            }
            return normalized;
        }
    }

    Optional<Entry> find(String scopeKey, String operation, String key);

    /** Returns true only when this invocation created the unique IN_PROGRESS reservation. */
    boolean reserve(String scopeKey, String operation, String key, String requestSha256, Instant now);

    void complete(
            String scopeKey,
            String operation,
            String key,
            String requestSha256,
            int httpStatus,
            String contentType,
            String etag,
            String location,
            String responseBodyBase64,
            Instant now);

    void markIndeterminate(String scopeKey, String operation, String key, String requestSha256, Instant now);

    /** Releases a reservation only when no successful mutation was observed (typically a 4xx response). */
    void release(String scopeKey, String operation, String key, String requestSha256);
}
