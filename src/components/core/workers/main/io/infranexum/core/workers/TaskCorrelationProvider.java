package io.infranexum.core.workers;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Optional;

/** Supplies the correlation identifier captured when a durable task is scheduled. */
@FunctionalInterface
public interface TaskCorrelationProvider {
    Optional<DomainIdentifier> current();

    /** Returns a provider used outside a request or tracing boundary. */
    static TaskCorrelationProvider none() {
        return Optional::<DomainIdentifier>empty;
    }
}
