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
        DomainIdentifier acquiredFromPartnerId,
        DomainIdentifier producerPartnerId) {
    /**
     * Backward-compatible acquisition contract used by E02 callers that do not yet know the canonical producer.
     *
     * <p>The resulting asset remains valid for acquisition/receipt but E03 operational readiness stays fail-closed
     * until {@code producerPartnerId} is set through the governed correction flow.</p>
     */
    public CreateAssetCommand(
            DomainIdentifier rsotObjectId, String assetType, DomainIdentifier owningOrganizationId,
            DomainIdentifier owningSubdivisionId, LocalDate acquisitionDate, BigDecimal acquisitionValue,
            String currencyCode, DomainIdentifier acquiredFromPartnerId) {
        this(rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId, acquisitionDate, acquisitionValue,
                currencyCode, acquiredFromPartnerId, null);
    }

    public CreateAssetCommand {
        Objects.requireNonNull(rsotObjectId, "rsotObjectId");
        Objects.requireNonNull(assetType, "assetType");
        Objects.requireNonNull(owningOrganizationId, "owningOrganizationId");
        Objects.requireNonNull(acquisitionDate, "acquisitionDate");
        Objects.requireNonNull(acquisitionValue, "acquisitionValue");
        Objects.requireNonNull(currencyCode, "currencyCode");
    }
}
