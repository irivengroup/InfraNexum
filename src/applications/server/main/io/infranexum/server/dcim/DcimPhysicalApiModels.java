package io.infranexum.server.dcim;

import io.infranexum.dcim.physical.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HTTP contracts for PGM-07-E05 rack occupancy and physical connectivity. */
final class DcimPhysicalApiModels {
    private DcimPhysicalApiModels() {}
    record PortTemplateRequest(@NotBlank @Size(max=24) String namePrefix,@Min(1) @Max(512) int count,@NotBlank String kind,@NotBlank @Size(max=32) String media,@NotBlank @Size(max=32) String connector){}
    record CreateModelRequest(@NotBlank String organizationId,@NotBlank String manufacturerPartnerId,@NotBlank @Size(min=3,max=64) String code,@NotBlank @Size(min=3,max=128) String displayName,@NotBlank @Size(max=32) String formFactor,@Min(1) @Max(100) int rackUnits,@Min(1) @Max(5000) int widthMm,@Min(1) @Max(5000) int depthMm,@DecimalMin(value="0",inclusive=false) BigDecimal weightKg,@NotEmpty List<@Valid PortTemplateRequest> portTemplates,@Size(max=4000) String description,@NotBlank @Size(min=2,max=1024) String reason){}
    record CreateRackRequest(@NotBlank String organizationId,@NotBlank String subdivisionId,@NotBlank String roomId,@NotBlank @Size(min=3,max=64) String code,@NotBlank @Size(min=3,max=128) String displayName,@Min(1) @Max(100) int heightU,@Min(1) @Max(5000) int widthMm,@Min(1) @Max(5000) int depthMm,@NotBlank @Size(min=2,max=1024) String reason){}
    record InstallEquipmentRequest(@NotBlank String organizationId,@NotBlank String subdivisionId,@NotBlank String rackId,@NotBlank String modelId,@NotBlank String rsotObjectId,String itamAssetId,@Size(max=128) String serialNumber,@Size(max=128) String assetTag,@Min(1) @Max(100) int startU,@NotBlank String face,@NotBlank @Size(min=2,max=1024) String reason){}
    record MoveEquipmentRequest(@NotBlank String rackId,@Min(1) @Max(100) int startU,@NotBlank String face,@NotBlank @Size(min=2,max=1024) String reason){}
    record StatusRequest(@NotBlank String targetStatus,@NotBlank @Size(min=2,max=1024) String reason){}
    record ConnectPortsRequest(@NotBlank String organizationId,@NotBlank String subdivisionId,@NotBlank String portAId,@NotBlank String portBId,@NotBlank @Size(max=128) String label,@NotBlank @Size(min=2,max=1024) String reason){}
    record ModelResponse(String id,String organizationId,String manufacturerPartnerId,String code,String displayName,String formFactor,int rackUnits,int widthMm,int depthMm,BigDecimal weightKg,List<PortTemplate> portTemplates,String status,String description,long version,Instant createdAt,Instant updatedAt,String lastReason){static ModelResponse from(EquipmentModel m){return new ModelResponse(m.id().toString(),m.organizationId().toString(),m.manufacturerPartnerId().toString(),m.code(),m.displayName(),m.formFactor(),m.rackUnits(),m.widthMm(),m.depthMm(),m.weightKg(),m.portTemplates(),m.status().wireValue(),m.description(),m.version(),m.createdAt(),m.updatedAt(),m.lastReason());}}
    record RackResponse(String id,String organizationId,String subdivisionId,String roomId,String code,String displayName,int heightU,int widthMm,int depthMm,String status,long version,Instant createdAt,Instant updatedAt,String lastReason){static RackResponse from(Rack r){return new RackResponse(r.id().toString(),r.organizationId().toString(),r.subdivisionId().toString(),r.roomId().toString(),r.code(),r.displayName(),r.heightU(),r.widthMm(),r.depthMm(),r.status().wireValue(),r.version(),r.createdAt(),r.updatedAt(),r.lastReason());}}
    record EquipmentResponse(String id,String organizationId,String subdivisionId,String rackId,String modelId,String rsotObjectId,String itamAssetId,String serialNumber,String assetTag,int startU,String face,String status,long version,Instant createdAt,Instant updatedAt,String lastReason){static EquipmentResponse from(Equipment e){return new EquipmentResponse(e.id().toString(),e.organizationId().toString(),e.subdivisionId().toString(),e.rackId().toString(),e.modelId().toString(),e.rsotObjectId().toString(),e.itamAssetId()==null?null:e.itamAssetId().toString(),e.serialNumber(),e.assetTag(),e.startU(),e.face(),e.status().wireValue(),e.version(),e.createdAt(),e.updatedAt(),e.lastReason());}}
    record PortResponse(String id,String organizationId,String equipmentId,String name,String kind,String media,String connector,boolean connected){static PortResponse from(PhysicalPort x){return new PortResponse(x.id().toString(),x.organizationId().toString(),x.equipmentId().toString(),x.name(),x.kind().wireValue(),x.media(),x.connector(),x.connected());}}
    record CableResponse(String id,String organizationId,String subdivisionId,String portAId,String portBId,String label,String media,String connector,String status,long version,Instant createdAt,Instant updatedAt,String lastReason){static CableResponse from(CableConnection x){return new CableResponse(x.id().toString(),x.organizationId().toString(),x.subdivisionId().toString(),x.portAId().toString(),x.portBId().toString(),x.label(),x.media(),x.connector(),x.status().wireValue(),x.version(),x.createdAt(),x.updatedAt(),x.lastReason());}}
}
