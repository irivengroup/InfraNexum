package io.infranexum.dcim.physical.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Audited point-to-point cable with explicit endpoints and physical inventory metadata. */
public final class CableConnection {
    private final DomainIdentifier id;
    private final DomainIdentifier organizationId;
    private final DomainIdentifier subdivisionId;
    private final DomainIdentifier portAId;
    private final DomainIdentifier portBId;
    private final String label;
    private final String media;
    private final String connector;
    private final CableType cableType;
    private final BigDecimal lengthMeters;
    private final DomainIdentifier manufacturerPartnerId;
    private final String manufacturerReference;
    private final PhysicalStatus status;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private CableConnection(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label, String media, String connector,
            CableType cableType, BigDecimal lengthMeters, DomainIdentifier manufacturerPartnerId,
            String manufacturerReference, PhysicalStatus status, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String reason) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.subdivisionId = Objects.requireNonNull(subdivisionId, "subdivisionId");
        this.portAId = Objects.requireNonNull(portAId, "portAId");
        this.portBId = Objects.requireNonNull(portBId, "portBId");
        if (portAId.equals(portBId)) throw new IllegalArgumentException("cable endpoints must differ");
        this.label = text(label, "label", 1, 128);
        this.media = text(media, "media", 1, 32).toLowerCase();
        this.connector = text(connector, "connector", 1, 32).toLowerCase();
        this.cableType = Objects.requireNonNull(cableType, "cableType");
        this.lengthMeters = positive(lengthMeters, "lengthMeters");
        this.manufacturerPartnerId = manufacturerPartnerId;
        this.manufacturerReference = nullable(manufacturerReference, "manufacturerReference", 160);
        if (this.manufacturerReference != null && manufacturerPartnerId == null) {
            throw new IllegalArgumentException("manufacturerPartnerId is required with manufacturerReference");
        }
        this.status = Objects.requireNonNull(status, "status");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = text(reason, "lastReason", 2, 1024);
    }

    public static CableConnection active(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label, String media, String connector,
            CableType cableType, BigDecimal lengthMeters, DomainIdentifier manufacturerPartnerId,
            String manufacturerReference, DomainIdentifier actor, String reason, Instant now) {
        return new CableConnection(id, organizationId, subdivisionId, portAId, portBId, label, media, connector,
                cableType, lengthMeters, manufacturerPartnerId, manufacturerReference, PhysicalStatus.ACTIVE,
                1, now, now, actor, actor, reason);
    }

    /** Backward-compatible factory for callers created before cable inventory metadata was introduced. */
    public static CableConnection active(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label, String media, String connector,
            DomainIdentifier actor, String reason, Instant now) {
        return active(id, organizationId, subdivisionId, portAId, portBId, label, media, connector,
                CableType.OTHER, BigDecimal.ONE, null, null, actor, reason, now);
    }

    public static CableConnection restore(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label, String media, String connector,
            CableType cableType, BigDecimal lengthMeters, DomainIdentifier manufacturerPartnerId,
            String manufacturerReference, PhysicalStatus status, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String reason) {
        return new CableConnection(id, organizationId, subdivisionId, portAId, portBId, label, media, connector,
                cableType, lengthMeters, manufacturerPartnerId, manufacturerReference, status, version,
                createdAt, updatedAt, createdBy, updatedBy, reason);
    }

    /** Backward-compatible restore factory for pre-metadata test fixtures. */
    public static CableConnection restore(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label, String media, String connector,
            PhysicalStatus status, long version, Instant createdAt, Instant updatedAt,
            DomainIdentifier createdBy, DomainIdentifier updatedBy, String reason) {
        return restore(id, organizationId, subdivisionId, portAId, portBId, label, media, connector,
                CableType.OTHER, BigDecimal.ONE, null, null, status, version, createdAt, updatedAt,
                createdBy, updatedBy, reason);
    }

    public CableConnection disconnect(DomainIdentifier actor, String reason, Instant now) {
        if (status != PhysicalStatus.ACTIVE) {
            throw new DcimPhysicalConflictException("DCIM_CABLE_NOT_ACTIVE", "only active cable can be disconnected");
        }
        return new CableConnection(id, organizationId, subdivisionId, portAId, portBId, label, media, connector,
                cableType, lengthMeters, manufacturerPartnerId, manufacturerReference,
                PhysicalStatus.DECOMMISSIONED, version + 1, createdAt, now, createdBy, actor, reason);
    }

    public DomainIdentifier id() { return id; }
    public DomainIdentifier organizationId() { return organizationId; }
    public DomainIdentifier subdivisionId() { return subdivisionId; }
    public DomainIdentifier portAId() { return portAId; }
    public DomainIdentifier portBId() { return portBId; }
    public String label() { return label; }
    public String media() { return media; }
    public String connector() { return connector; }
    public CableType cableType() { return cableType; }
    public BigDecimal lengthMeters() { return lengthMeters; }
    public DomainIdentifier manufacturerPartnerId() { return manufacturerPartnerId; }
    public String manufacturerReference() { return manufacturerReference; }
    public PhysicalStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public DomainIdentifier createdBy() { return createdBy; }
    public DomainIdentifier updatedBy() { return updatedBy; }
    public String lastReason() { return lastReason; }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("100000")) > 0) {
            throw new IllegalArgumentException(field + " must be > 0 and <= 100000");
        }
        return value.stripTrailingZeros();
    }

    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " invalid");
        String normalized = value.strip();
        if (normalized.length() < min || normalized.length() > max) throw new IllegalArgumentException(field + " invalid");
        return normalized;
    }

    private static String nullable(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        return text(value, field, 1, max);
    }
}
