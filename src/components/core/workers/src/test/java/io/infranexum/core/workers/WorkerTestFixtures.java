package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.events.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Shared deterministic fixtures for the workers module contract tests. */
final class WorkerTestFixtures {
    static final Instant START = Instant.parse("2026-08-10T12:00:00Z");
    static final Duration LEASE = Duration.ofSeconds(10);
    static final TaskType TYPE = new TaskType("inventory.refresh");
    static final RetryPolicy RETRY = new FixedRetryPolicy(3, Duration.ofSeconds(5));

    private WorkerTestFixtures() {}

    static TaskId id(long sequence) {
        long millis = START.toEpochMilli() + sequence;
        long most = (millis << 16) | 0x7000L | (sequence & 0x0fffL);
        long least = 0x8000_0000_0000_0000L | (sequence & 0x3fff_ffff_ffff_ffffL);
        return new TaskId(new DomainIdentifier(new UUID(most, least)));
    }

    static TaskSubmission submission(String key) {
        return new TaskSubmission(TYPE, key, Map.of("site", "paris"), START);
    }

    static final class FixedRetryPolicy implements RetryPolicy {
        private final int maximumAttempts;
        private final Duration delay;

        FixedRetryPolicy(int maximumAttempts, Duration delay) {
            if (maximumAttempts < 1) {
                throw new IllegalArgumentException("maximumAttempts must be positive");
            }
            this.maximumAttempts = maximumAttempts;
            this.delay = delay;
        }

        @Override
        public int maximumAttempts() {
            return maximumAttempts;
        }

        @Override
        public Duration delayAfterFailure(int attempts) {
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be positive");
            }
            return delay;
        }
    }
}
