package io.infranexum.organization.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.TemporalScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for effective-dated governance scopes. */
public interface TemporalScopeRepository {
    void insert(TemporalScope scope);

    Optional<TemporalScope> findById(DomainIdentifier organizationId, DomainIdentifier id);

    List<TemporalScope> effective(DomainIdentifier organizationId, Instant at);
}
