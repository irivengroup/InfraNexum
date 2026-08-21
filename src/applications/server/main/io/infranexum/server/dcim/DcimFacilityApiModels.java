package io.infranexum.server.dcim;

import io.infranexum.dcim.facility.domain.FacilityNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HTTP DTOs for the PGM-07-E04 DCIM physical hierarchy. */
final class DcimFacilityApiModels {
    private DcimFacilityApiModels() {}

    record CreateFacilityRequest(
            @NotBlank String organizationId,
            @NotBlank String subdivisionId,
            String parentId,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,63}") String code,
            @NotBlank @Size(min = 3, max = 128) String displayName,
            @Size(max = 128) String addressLine1,
            @Size(max = 128) String addressLine2,
            @Size(max = 16) String postalCode,
            @Size(max = 64) String city,
            @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            @Size(max = 64) String timezone,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            Integer floorCount,
            Integer levelNumber,
            @DecimalMin(value = "0", inclusive = false) BigDecimal areaM2,
            @DecimalMin(value = "0", inclusive = false) BigDecimal levelHeightM,
            @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKw,
            String accessRestriction,
            String zoneType,
            @Size(max = 4000) String description,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record UpdateFacilityRequest(
            @NotBlank @Size(min = 3, max = 128) String displayName,
            @Size(max = 128) String addressLine1,
            @Size(max = 128) String addressLine2,
            @Size(max = 16) String postalCode,
            @Size(max = 64) String city,
            @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            @Size(max = 64) String timezone,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            Integer floorCount,
            Integer levelNumber,
            @DecimalMin(value = "0", inclusive = false) BigDecimal areaM2,
            @DecimalMin(value = "0", inclusive = false) BigDecimal levelHeightM,
            @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKw,
            String accessRestriction,
            String zoneType,
            @Size(max = 4000) String description,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record FacilityStatusRequest(
            @NotBlank String targetStatus,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record FacilityResponse(
            String id,
            String kind,
            String organizationId,
            String subdivisionId,
            String parentId,
            String scopeId,
            String code,
            String displayName,
            String status,
            String addressLine1,
            String addressLine2,
            String postalCode,
            String city,
            String countryCode,
            String timezone,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer floorCount,
            Integer levelNumber,
            BigDecimal areaM2,
            BigDecimal levelHeightM,
            BigDecimal capacityKw,
            String accessRestriction,
            String zoneType,
            String description,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy,
            String lastReason) {
        static FacilityResponse from(FacilityNode node) {
            return new FacilityResponse(
                    node.id().toString(), node.kind().wireValue(), node.organizationId().toString(),
                    node.subdivisionId().toString(), text(node.parentId()), node.scopeId().toString(),
                    node.code().value(), node.displayName(), node.status().wireValue(), node.addressLine1(), node.addressLine2(),
                    node.postalCode(), node.city(), node.countryCode(), node.timezone(),
                    node.latitude(), node.longitude(), node.floorCount(), node.levelNumber(), node.areaM2(),
                    node.levelHeightM(), node.capacityKw(), node.accessRestriction(), node.zoneType(), node.description(),
                    node.version(), node.createdAt(), node.updatedAt(), node.createdBy().toString(), node.updatedBy().toString(),
                    node.lastReason());
        }
    }

    record FacilityPageResponse(@NotNull List<FacilityResponse> items, String nextCursor) {}

    private static String text(io.infranexum.core.contracts.DomainIdentifier value) {
        return value == null ? null : value.toString();
    }
}
