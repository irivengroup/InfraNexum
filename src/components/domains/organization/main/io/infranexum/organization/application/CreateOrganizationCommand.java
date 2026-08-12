package io.infranexum.organization.application;
import io.infranexum.core.contracts.DomainIdentifier;
public record CreateOrganizationCommand(String code,String displayName,String legalName,String countryCode,String defaultLanguage,String timezone,String currency,DomainIdentifier parentOrganizationId) {}
