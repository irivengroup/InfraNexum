package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.LocalDate;

/** Complete software-license contract draft without raw secret license-key material. */
public record CreateLicenseCommand(DomainIdentifier assetId,DomainIdentifier publisherPartnerId,String contractNumber,String licenseModel,
        String usageRights,long entitlementQuantity,LocalDate startsOn,LocalDate endsOn,LocalDate publisherSupportEndDate,
        String proofReference,String source) {}
