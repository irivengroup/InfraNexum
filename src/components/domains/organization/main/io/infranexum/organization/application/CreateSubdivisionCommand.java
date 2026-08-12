package io.infranexum.organization.application;
import io.infranexum.core.contracts.DomainIdentifier;
public record CreateSubdivisionCommand(DomainIdentifier organizationId,String code,String displayName,String description,String type,DomainIdentifier parentSubdivisionId) {}
