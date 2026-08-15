package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.LocalDate;

/** Third-party support coverage bound to one governed provider authorization. */
public record CreateSupportCoverageCommand(DomainIdentifier assetId,DomainIdentifier providerPartnerId,DomainIdentifier authorizationId,
        String contractReference,String coverageType,String serviceLevel,LocalDate startsOn,LocalDate endsOn,String proofReference) {}
