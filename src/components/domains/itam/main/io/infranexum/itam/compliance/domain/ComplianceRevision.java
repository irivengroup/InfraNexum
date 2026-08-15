package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Immutable contractual snapshot retained for evidence/version history. */
public record ComplianceRevision(String recordType,DomainIdentifier recordId,long version,ComplianceStatus status,
        String proofReference,String reason,String snapshotJson,Instant recordedAt,DomainIdentifier recordedBy) {
    public ComplianceRevision {
        recordType=ComplianceTexts.text(recordType,"recordType",2,32);Objects.requireNonNull(recordId,"recordId");
        if(version<1)throw new IllegalArgumentException("version must be positive");Objects.requireNonNull(status,"status");
        proofReference=ComplianceTexts.optional(proofReference,"proofReference",240);reason=ComplianceTexts.text(reason,"reason",2,1024);
        snapshotJson=ComplianceTexts.text(snapshotJson,"snapshotJson",2,12000);Objects.requireNonNull(recordedAt,"recordedAt");Objects.requireNonNull(recordedBy,"recordedBy");
    }
}
