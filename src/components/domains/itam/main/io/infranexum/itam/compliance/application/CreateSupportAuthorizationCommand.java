package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.LocalDate;
import java.util.Set;

/** Governed third-party support authorization scope. */
public record CreateSupportAuthorizationCommand(DomainIdentifier providerPartnerId,DomainIdentifier organizationId,
        Set<DomainIdentifier> supportedManufacturerIds,Set<String> supportedObjectTypes,Set<DomainIdentifier> subdivisionScopes,
        String serviceHours,String timeZoneId,Set<String> serviceLevels,Set<String> escalationContactTypes,
        LocalDate validFrom,LocalDate validUntil) {}
