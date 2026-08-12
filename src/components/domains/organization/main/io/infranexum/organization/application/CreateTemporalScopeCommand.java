package io.infranexum.organization.application;
import io.infranexum.core.contracts.DomainIdentifier; import java.time.Instant;
public record CreateTemporalScopeCommand(DomainIdentifier organizationId,DomainIdentifier subdivisionId,String type,Instant validFrom,Instant validTo) {}
