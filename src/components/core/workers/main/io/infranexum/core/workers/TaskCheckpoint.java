package io.infranexum.core.workers;

import java.time.Instant;
import java.util.Objects;

/** Durable opaque resume token emitted by a running task. */
public record TaskCheckpoint(long sequence, String token, Instant persistedAt) {
    private static final int MAX_TOKEN_LENGTH = 4_096;

    public TaskCheckpoint {
        if (sequence < 1) {
            throw new IllegalArgumentException("checkpoint sequence must be positive");
        }
        token = Objects.requireNonNull(token, "token");
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("checkpoint token must contain 1-4096 characters");
        }
        Objects.requireNonNull(persistedAt, "persistedAt");
    }
}
