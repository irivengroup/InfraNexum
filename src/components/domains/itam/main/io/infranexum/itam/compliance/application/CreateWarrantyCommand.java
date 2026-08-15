package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.LocalDate;

/** Complete manufacturer-warranty draft command. */
public record CreateWarrantyCommand(DomainIdentifier assetId,DomainIdentifier manufacturerPartnerId,DomainIdentifier warrantyTypeId,
        String coverageLevel,LocalDate warrantyStartDate,LocalDate warrantyEndDate,LocalDate manufacturerSupportEndDate,
        String contractOrCertificateNumber,String proofReference,String source) {}
