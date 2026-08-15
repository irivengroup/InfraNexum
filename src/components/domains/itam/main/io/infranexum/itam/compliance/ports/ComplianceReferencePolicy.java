package io.infranexum.itam.compliance.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.compliance.domain.SupportProviderAuthorization;
import java.time.LocalDate;
import java.util.Set;

/** Weak-reference authority validation; ITAM compliance never writes another bounded context. */
public interface ComplianceReferencePolicy {
    void validateManufacturer(Asset asset, DomainIdentifier manufacturerPartnerId, LocalDate effectiveOn);
    void validatePublisher(Asset asset, DomainIdentifier publisherPartnerId, LocalDate effectiveOn);
    void validateSupportProvider(Asset asset, DomainIdentifier providerPartnerId, LocalDate effectiveOn, Set<String> escalationContactTypes);
    void validateSupportAuthorizationDefinition(DomainIdentifier providerPartnerId,DomainIdentifier organizationId,
            Set<DomainIdentifier> manufacturerIds,Set<String> objectTypes,Set<DomainIdentifier> subdivisionScopes,
            Set<String> escalationContactTypes,LocalDate effectiveOn);
    String canonicalObjectType(Asset asset);
    void validateWarrantyType(DomainIdentifier warrantyTypeId);
    void validateSupportAuthorization(Asset asset, SupportProviderAuthorization authorization, String serviceLevel, LocalDate effectiveOn);
}
