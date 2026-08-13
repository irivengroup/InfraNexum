package io.infranexum.rsot.application;

import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityMatrixEntry;
import io.infranexum.rsot.domain.ContextRelationship;
import io.infranexum.rsot.domain.RsotException;
import io.infranexum.rsot.ports.RsotRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fail-closed authority resolver and read access to the approved RSOT governance baseline. */
public final class RsotAuthorityService {
    private final RsotRepository repository;
    private final Clock clock;

    public RsotAuthorityService(RsotRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AttributeAuthorityPolicy resolve(String objectType, String attributePath) {
        return resolve(objectType, attributePath, clock.instant());
    }

    public AttributeAuthorityPolicy resolve(String objectType, String attributePath, Instant at) {
        Objects.requireNonNull(at, "at");
        List<AttributeAuthorityPolicy> matches = repository.authorityPolicies().stream()
                .filter(policy -> policy.activeAt(at) && policy.matches(objectType, attributePath))
                .toList();
        if (matches.isEmpty()) {
            throw new RsotException("RSOT_AUTHORITY_NOT_CONFIGURED", "no approved authority policy matches the requested attribute");
        }
        if (matches.size() != 1) {
            throw new RsotException("RSOT_AUTHORITY_AMBIGUOUS", "multiple active authority policies match the requested attribute");
        }
        return matches.getFirst();
    }

    public List<AuthorityMatrixEntry> authorityMatrix() {
        return repository.authorityMatrix();
    }

    public List<ContextRelationship> contextMap() {
        return repository.contextMap();
    }
}
