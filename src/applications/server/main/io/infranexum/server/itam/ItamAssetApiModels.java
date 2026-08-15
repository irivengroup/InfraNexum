package io.infranexum.server.itam;

import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** HTTP DTOs for PGM-07-E02 asset lifecycle and custody history. */
final class ItamAssetApiModels {
    private ItamAssetApiModels() {}

    record CreateAssetRequest(
            @NotBlank String rsotObjectId,
            @NotBlank String assetType,
            @NotBlank String owningOrganizationId,
            String owningSubdivisionId,
            @NotNull LocalDate acquisitionDate,
            @NotNull @DecimalMin("0.0000") BigDecimal acquisitionValue,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
            String acquiredFromPartnerId,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record AssetTransitionRequest(
            String custodianKind,
            String custodianId,
            @NotBlank @Size(min = 2, max = 1024) String reason,
            @Size(max = 240) String evidenceReference) {}

    record AssetResponse(
            String id,
            String rsotObjectId,
            String assetType,
            String owningOrganizationId,
            String owningSubdivisionId,
            LocalDate acquisitionDate,
            BigDecimal acquisitionValue,
            String currencyCode,
            String acquiredFromPartnerId,
            String lifecycleStatus,
            String custodianKind,
            String custodianId,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static AssetResponse from(Asset asset) {
            return new AssetResponse(
                    asset.id().toString(), asset.rsotObjectId().toString(), asset.assetType().wireValue(),
                    asset.owningOrganizationId().toString(), text(asset.owningSubdivisionId()), asset.acquisitionDate(),
                    asset.acquisitionValue().amount(), asset.acquisitionValue().currencyCode(), text(asset.acquiredFromPartnerId()),
                    asset.lifecycleStatus().wireValue(), asset.custodian().kind().wireValue(),
                    text(asset.custodian().referenceId()), asset.version(), asset.createdAt(), asset.updatedAt());
        }
    }

    record AssetPageResponse(List<AssetResponse> items, String nextCursor) {}

    record CustodyEventResponse(
            String eventId,
            long sequence,
            String eventType,
            String fromStatus,
            String toStatus,
            String custodianKind,
            String custodianId,
            Instant occurredAt,
            String actorId,
            String correlationId,
            String reason,
            String evidenceReference) {
        static CustodyEventResponse from(AssetCustodyEvent event) {
            return new CustodyEventResponse(
                    event.eventId().toString(), event.sequence(), event.eventType().name().toLowerCase(java.util.Locale.ROOT),
                    event.fromStatus() == null ? null : event.fromStatus().wireValue(), event.toStatus().wireValue(),
                    event.custodian().kind().wireValue(), text(event.custodian().referenceId()), event.occurredAt(),
                    event.actorId().toString(), event.correlationId().toString(), event.reason(), event.evidenceReference());
        }
    }

    private static String text(io.infranexum.core.contracts.DomainIdentifier value) {
        return value == null ? null : value.toString();
    }
}
