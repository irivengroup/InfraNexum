package io.infranexum.dcim.physical.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical multi-manufacturer DCIM equipment footprint, classification and port model. */
public final class EquipmentModel {
    private final DomainIdentifier id;
    private final DomainIdentifier organizationId;
    private final DomainIdentifier manufacturerPartnerId;
    private final String code;
    private final String displayName;
    private final EquipmentCategory category;
    private final EquipmentType equipmentType;
    private final String manufacturerReference;
    private final String formFactor;
    private final int rackUnits;
    private final int widthMm;
    private final int depthMm;
    private final BigDecimal weightKg;
    private final List<PortTemplate> portTemplates;
    private final PhysicalStatus status;
    private final String description;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private EquipmentModel(
            DomainIdentifier id,
            DomainIdentifier organizationId,
            DomainIdentifier manufacturerPartnerId,
            String code,
            String displayName,
            EquipmentCategory category,
            EquipmentType equipmentType,
            String manufacturerReference,
            String formFactor,
            int rackUnits,
            int widthMm,
            int depthMm,
            BigDecimal weightKg,
            List<PortTemplate> portTemplates,
            PhysicalStatus status,
            String description,
            long version,
            Instant createdAt,
            Instant updatedAt,
            DomainIdentifier createdBy,
            DomainIdentifier updatedBy,
            String lastReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.manufacturerPartnerId = Objects.requireNonNull(manufacturerPartnerId, "manufacturerPartnerId");
        this.code = token(code, "code", 3, 64);
        this.displayName = text(displayName, "displayName", 3, 128);
        this.category = Objects.requireNonNull(category, "category");
        this.equipmentType = Objects.requireNonNull(equipmentType, "equipmentType");
        if (equipmentType.category() != category) {
            throw new IllegalArgumentException("equipmentType does not belong to category");
        }
        this.manufacturerReference = nullableText(manufacturerReference, "manufacturerReference", 160);
        this.formFactor = token(formFactor, "formFactor", 2, 32);
        validateDimensions(equipmentType, rackUnits, widthMm, depthMm, weightKg);
        this.rackUnits = rackUnits;
        this.widthMm = widthMm;
        this.depthMm = depthMm;
        this.weightKg = normalizedWeight(weightKg);
        this.portTemplates = List.copyOf(Objects.requireNonNull(portTemplates, "portTemplates"));
        long ports = this.portTemplates.stream().mapToLong(PortTemplate::count).sum();
        if (ports > 2048) throw new IllegalArgumentException("equipment model port count exceeds 2048");
        this.status = Objects.requireNonNull(status, "status");
        this.description = nullableText(description, "description", 4096);
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = text(lastReason, "lastReason", 2, 1024);
    }

    public static EquipmentModel draft(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier manufacturerPartnerId,
            String code, String displayName, EquipmentCategory category, EquipmentType equipmentType,
            String manufacturerReference, String formFactor, int rackUnits, int widthMm, int depthMm,
            BigDecimal weightKg, List<PortTemplate> templates, String description, DomainIdentifier actor,
            String reason, Instant now) {
        return new EquipmentModel(id, organizationId, manufacturerPartnerId, code, displayName, category,
                equipmentType, manufacturerReference, formFactor, rackUnits, widthMm, depthMm, weightKg,
                templates, PhysicalStatus.DRAFT, description, 1, now, now, actor, actor, reason);
    }

    /** Backward-compatible factory for callers created before equipment taxonomy was introduced. */
    public static EquipmentModel draft(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier manufacturerPartnerId,
            String code, String displayName, String formFactor, int rackUnits, int widthMm, int depthMm,
            BigDecimal weightKg, List<PortTemplate> templates, String description, DomainIdentifier actor,
            String reason, Instant now) {
        return draft(id, organizationId, manufacturerPartnerId, code, displayName, EquipmentCategory.OTHER,
                EquipmentType.OTHER_EQUIPMENT, null, formFactor, rackUnits, widthMm, depthMm, weightKg,
                templates, description, actor, reason, now);
    }

    public static EquipmentModel restore(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier manufacturerPartnerId,
            String code, String displayName, EquipmentCategory category, EquipmentType equipmentType,
            String manufacturerReference, String formFactor, int rackUnits, int widthMm, int depthMm,
            BigDecimal weightKg, List<PortTemplate> templates, PhysicalStatus status, String description,
            long version, Instant createdAt, Instant updatedAt, DomainIdentifier createdBy,
            DomainIdentifier updatedBy, String lastReason) {
        return new EquipmentModel(id, organizationId, manufacturerPartnerId, code, displayName, category,
                equipmentType, manufacturerReference, formFactor, rackUnits, widthMm, depthMm, weightKg,
                templates, status, description, version, createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    /** Backward-compatible restore factory for pre-taxonomy test fixtures. */
    public static EquipmentModel restore(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier manufacturerPartnerId,
            String code, String displayName, String formFactor, int rackUnits, int widthMm, int depthMm,
            BigDecimal weightKg, List<PortTemplate> templates, PhysicalStatus status, String description,
            long version, Instant createdAt, Instant updatedAt, DomainIdentifier createdBy,
            DomainIdentifier updatedBy, String lastReason) {
        return restore(id, organizationId, manufacturerPartnerId, code, displayName, EquipmentCategory.OTHER,
                EquipmentType.OTHER_EQUIPMENT, null, formFactor, rackUnits, widthMm, depthMm, weightKg,
                templates, status, description, version, createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    public EquipmentModel changeStatus(PhysicalStatus target, DomainIdentifier actor, String reason, Instant now) {
        if (status == PhysicalStatus.ARCHIVED) {
            throw new DcimPhysicalConflictException("DCIM_MODEL_IMMUTABLE", "archived model is immutable");
        }
        if (target == PhysicalStatus.MAINTENANCE) {
            throw new DcimPhysicalConflictException("DCIM_MODEL_STATUS_INVALID", "equipment models do not enter maintenance");
        }
        return new EquipmentModel(id, organizationId, manufacturerPartnerId, code, displayName, category,
                equipmentType, manufacturerReference, formFactor, rackUnits, widthMm, depthMm, weightKg,
                portTemplates, target, description, version + 1, createdAt, now, createdBy, actor, reason);
    }

    public DomainIdentifier id() { return id; }
    public DomainIdentifier organizationId() { return organizationId; }
    public DomainIdentifier manufacturerPartnerId() { return manufacturerPartnerId; }
    public String code() { return code; }
    public String displayName() { return displayName; }
    public EquipmentCategory category() { return category; }
    public EquipmentType equipmentType() { return equipmentType; }
    public String manufacturerReference() { return manufacturerReference; }
    public String formFactor() { return formFactor; }
    public int rackUnits() { return rackUnits; }
    public int widthMm() { return widthMm; }
    public int depthMm() { return depthMm; }
    public BigDecimal weightKg() { return weightKg; }
    public List<PortTemplate> portTemplates() { return portTemplates; }
    public PhysicalStatus status() { return status; }
    public String description() { return description; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public DomainIdentifier createdBy() { return createdBy; }
    public DomainIdentifier updatedBy() { return updatedBy; }
    public String lastReason() { return lastReason; }

    private static void validateDimensions(EquipmentType type, int rackUnits, int widthMm, int depthMm, BigDecimal weightKg) {
        if (rackUnits < 0 || rackUnits > 100 || widthMm < 0 || widthMm > 5000 || depthMm < 0 || depthMm > 5000) {
            throw new IllegalArgumentException("model dimensions are invalid");
        }
        BigDecimal weight = normalizedWeight(weightKg);
        if (weight.signum() < 0) throw new IllegalArgumentException("weightKg must not be negative");
        if (type.rackMountable() && (rackUnits < 1 || widthMm < 1 || depthMm < 1 || weight.signum() <= 0)) {
            throw new IllegalArgumentException("rack-mountable models require a positive physical footprint");
        }
    }

    private static BigDecimal normalizedWeight(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String token(String value, String field, int min, int max) {
        String normalized = text(value, field, min, max);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " invalid");
        String normalized = value.strip();
        if (normalized.length() < min || normalized.length() > max) throw new IllegalArgumentException(field + " length/content is invalid");
        return normalized;
    }

    private static String nullableText(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        return text(value, field, 1, max);
    }
}
