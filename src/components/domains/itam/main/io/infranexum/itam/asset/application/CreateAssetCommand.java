package io.infranexum.itam.asset.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Acquisition command; lifecycle state is always ACQUIRED and cannot be caller-selected. */
public record CreateAssetCommand(
        DomainIdentifier rsotObjectId,
        String assetType,
        DomainIdentifier owningOrganizationId,
        DomainIdentifier owningSubdivisionId,
        LocalDate acquisitionDate,
        BigDecimal acquisitionValue,
        String currencyCode,
        DomainIdentifier acquiredFromPartnerId) {
    public CreateAssetCommand {
        Objects.requireNonNull(rsotObjectId, "rsotObjectId");
        Objects.requireNonNull(assetType, "assetType");
        Objects.requireNonNull(owningOrganizationId, "owningOrganizationId");
        Objects.requireNonNull(acquisitionDate, "acquisitionDate");
        Objects.requireNonNull(acquisitionValue, "acquisitionValue");
        Objects.requireNonNull(currencyCode, "currencyCode");
    }
}
