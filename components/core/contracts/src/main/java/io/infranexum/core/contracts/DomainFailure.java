package io.infranexum.core.contracts;

import java.util.Map;
import java.util.Objects;

/** Framework-independent domain failure suitable for translation at API, CLI and event boundaries. */
public record DomainFailure(DomainErrorCode code, String message, Map<String, String> details) {
    public DomainFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("failure message must not be blank");
        }
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }
}
