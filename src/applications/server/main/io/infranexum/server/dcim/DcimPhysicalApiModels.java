package io.infranexum.server.dcim;

import io.infranexum.dcim.physical.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HTTP contracts for governed DCIM equipment, rack placement, ports and cabling. */
final class DcimPhysicalApiModels {
    private DcimPhysicalApiModels() {}

    record PortTemplateRequest(
            @NotBlank @Size(max = 24) String namePrefix,
            @Min(1) @Max(512) int count,
            @NotBlank String kind,
            @NotBlank @Size(max = 32) String media,
            @NotBlank @Size(max = 32) String connector) {}

    record CreateModelRequest(
            @NotBlank String organizationId,
            @NotBlank String manufacturerPartnerId,
            @Size(min = 3, max = 64) String code,
            @NotBlank @Size(min = 3, max = 128) String displayName,
            String category,
            String equipmentType,
            @Size(max = 160) String manufacturerReference,
            @NotBlank @Size(max = 32) String formFactor,
            @Min(0) @Max(100) int rackUnits,
            @Min(0) @Max(5000) int widthMm,
            @Min(0) @Max(5000) int depthMm,
            @DecimalMin(value = "0", inclusive = true) BigDecimal weightKg,
            @Size(max = 64) List<@Valid PortTemplateRequest> portTemplates,
            @Size(max = 4000) String description,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record CreateRackRequest(
            @NotBlank String organizationId,
            @NotBlank String subdivisionId,
            @NotBlank String roomId,
            @Size(min = 3, max = 64) String code,
            @NotBlank @Size(min = 3, max = 128) String displayName,
            @Min(1) @Max(100) int heightU,
            @Min(1) @Max(5000) int widthMm,
            @Min(1) @Max(5000) int depthMm,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record InstallEquipmentRequest(
            @NotBlank String organizationId, @NotBlank String subdivisionId, @NotBlank String rackId,
            @NotBlank String modelId, @NotBlank String rsotObjectId, String itamAssetId,
            @Size(max = 128) String serialNumber, @Size(max = 128) String assetTag,
            @Min(1) @Max(100) int startU, @NotBlank String face,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record MoveEquipmentRequest(
            @NotBlank String rackId, @Min(1) @Max(100) int startU, @NotBlank String face,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record StatusRequest(
            @NotBlank String targetStatus,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record ConnectPortsRequest(
            @NotBlank String organizationId,
            @NotBlank String subdivisionId,
            @NotBlank String portAId,
            @NotBlank String portBId,
            @NotBlank @Size(max = 128) String label,
            String cableType,
            @DecimalMin(value = "0", inclusive = false) BigDecimal lengthMeters,
            String manufacturerPartnerId,
            @Size(max = 160) String manufacturerReference,
            @NotBlank @Size(min = 2, max = 1024) String reason) {}

    record EquipmentTypeResponse(String value, boolean rackMountable) {
        static EquipmentTypeResponse from(EquipmentType type) {
            return new EquipmentTypeResponse(type.wireValue(), type.rackMountable());
        }
    }

    record EquipmentCategoryResponse(String value, List<EquipmentTypeResponse> types) {
        static EquipmentCategoryResponse from(EquipmentCategory category) {
            return new EquipmentCategoryResponse(category.wireValue(),
                    EquipmentType.forCategory(category).stream().map(EquipmentTypeResponse::from).toList());
        }
    }

    record EquipmentTaxonomyResponse(List<EquipmentCategoryResponse> categories, List<String> cableTypes) {
        static EquipmentTaxonomyResponse create() {
            return new EquipmentTaxonomyResponse(
                    java.util.Arrays.stream(EquipmentCategory.values()).map(EquipmentCategoryResponse::from).toList(),
                    java.util.Arrays.stream(CableType.values()).map(CableType::wireValue).toList());
        }
    }

    record ModelResponse(
            String id, String organizationId, String manufacturerPartnerId, String code, String displayName,
            String category, String equipmentType, boolean rackMountable, String manufacturerReference, String formFactor,
            int rackUnits, int widthMm, int depthMm, BigDecimal weightKg, List<PortTemplate> portTemplates,
            String status, String description, long version, Instant createdAt, Instant updatedAt, String lastReason) {
        static ModelResponse from(EquipmentModel model) {
            return new ModelResponse(model.id().toString(), model.organizationId().toString(),
                    model.manufacturerPartnerId().toString(), model.code(), model.displayName(),
                    model.category().wireValue(), model.equipmentType().wireValue(), model.equipmentType().rackMountable(), model.manufacturerReference(),
                    model.formFactor(), model.rackUnits(), model.widthMm(), model.depthMm(), model.weightKg(),
                    model.portTemplates(), model.status().wireValue(), model.description(), model.version(),
                    model.createdAt(), model.updatedAt(), model.lastReason());
        }
    }

    record RackResponse(String id,String organizationId,String subdivisionId,String roomId,String code,String displayName,int heightU,int widthMm,int depthMm,String status,long version,Instant createdAt,Instant updatedAt,String lastReason){static RackResponse from(Rack r){return new RackResponse(r.id().toString(),r.organizationId().toString(),r.subdivisionId().toString(),r.roomId().toString(),r.code(),r.displayName(),r.heightU(),r.widthMm(),r.depthMm(),r.status().wireValue(),r.version(),r.createdAt(),r.updatedAt(),r.lastReason());}}
    record EquipmentResponse(String id,String organizationId,String subdivisionId,String rackId,String modelId,String rsotObjectId,String itamAssetId,String serialNumber,String assetTag,int startU,String face,String status,long version,Instant createdAt,Instant updatedAt,String lastReason){static EquipmentResponse from(Equipment e){return new EquipmentResponse(e.id().toString(),e.organizationId().toString(),e.subdivisionId().toString(),e.rackId().toString(),e.modelId().toString(),e.rsotObjectId().toString(),e.itamAssetId()==null?null:e.itamAssetId().toString(),e.serialNumber(),e.assetTag(),e.startU(),e.face(),e.status().wireValue(),e.version(),e.createdAt(),e.updatedAt(),e.lastReason());}}
    record PortResponse(String id,String organizationId,String equipmentId,String name,String kind,String media,String connector,boolean connected){static PortResponse from(PhysicalPort x){return new PortResponse(x.id().toString(),x.organizationId().toString(),x.equipmentId().toString(),x.name(),x.kind().wireValue(),x.media(),x.connector(),x.connected());}}

    record CableResponse(
            String id, String organizationId, String subdivisionId, String portAId, String portBId,
            String label, String media, String connector, String cableType, BigDecimal lengthMeters,
            String manufacturerPartnerId, String manufacturerReference, String status, long version,
            Instant createdAt, Instant updatedAt, String lastReason) {
        static CableResponse from(CableConnection cable) {
            return new CableResponse(cable.id().toString(), cable.organizationId().toString(),
                    cable.subdivisionId().toString(), cable.portAId().toString(), cable.portBId().toString(),
                    cable.label(), cable.media(), cable.connector(), cable.cableType().wireValue(),
                    cable.lengthMeters(), text(cable.manufacturerPartnerId()), cable.manufacturerReference(),
                    cable.status().wireValue(), cable.version(), cable.createdAt(), cable.updatedAt(), cable.lastReason());
        }
    }

    private static String text(io.infranexum.core.contracts.DomainIdentifier value) {
        return value == null ? null : value.toString();
    }
}
