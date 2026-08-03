package io.infranexum.core.events;

import java.util.List;
import java.util.Objects;

/** Committed result plus non-fatal failures from post-commit notification hooks. */
public record TransactionOutcome<T>(T value, List<String> postCommitFailures) {
    public TransactionOutcome {
        postCommitFailures = List.copyOf(Objects.requireNonNull(postCommitFailures, "postCommitFailures"));
    }

    public boolean postCommitSignalsSucceeded() {
        return postCommitFailures.isEmpty();
    }
}
