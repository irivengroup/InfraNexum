package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.LocalDate;
import java.util.Objects;

/** Deterministic deadline alert generated from contractual dates, never from updated_at. */
public record ComplianceAlert(ComplianceAlertKind kind, DomainIdentifier recordId, DomainIdentifier assetId,
                              LocalDate dueDate, long daysRemaining, int thresholdDays) {
    public ComplianceAlert {
        Objects.requireNonNull(kind,"kind");Objects.requireNonNull(recordId,"recordId");Objects.requireNonNull(assetId,"assetId");Objects.requireNonNull(dueDate,"dueDate");
        if(daysRemaining<0)throw new IllegalArgumentException("daysRemaining cannot be negative");
        if(thresholdDays<1)throw new IllegalArgumentException("thresholdDays must be positive");
    }
}
