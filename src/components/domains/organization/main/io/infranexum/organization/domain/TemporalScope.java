package io.infranexum.organization.domain;
import io.infranexum.core.contracts.DomainIdentifier; import java.time.Instant; import java.util.Objects;
/** Immutable effective-dated organization or subdivision governance scope. */
public record TemporalScope(DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId, ScopeType type,
        Instant validFrom, Instant validTo, long version, Instant createdAt) {
    public TemporalScope { Objects.requireNonNull(id,"id"); Objects.requireNonNull(organizationId,"organizationId"); Objects.requireNonNull(type,"type"); Objects.requireNonNull(validFrom,"validFrom"); Objects.requireNonNull(createdAt,"createdAt"); if(validTo!=null&&!validTo.isAfter(validFrom))throw new IllegalArgumentException("validTo must be after validFrom"); if(version<0)throw new IllegalArgumentException("version must be non-negative"); }
    public boolean effectiveAt(Instant instant){Objects.requireNonNull(instant,"instant");return !instant.isBefore(validFrom)&&(validTo==null||instant.isBefore(validTo));}
}
