package io.infranexum.itam.asset.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * ITAM patrimonial asset aggregate for PGM-07-E02.
 *
 * <p>RSOT remains owner of canonical operational identity. ITAM stores only the weak canonical
 * reference and the patrimonial lifecycle/custody state. Every state/custody mutation increments
 * the optimistic version and must be accompanied by an append-only custody event.</p>
 */
public final class Asset {
    private final DomainIdentifier id;
    private final DomainIdentifier rsotObjectId;
    private final AssetType assetType;
    private final DomainIdentifier owningOrganizationId;
    private final DomainIdentifier owningSubdivisionId;
    private final LocalDate acquisitionDate;
    private final AssetValue acquisitionValue;
    private final DomainIdentifier acquiredFromPartnerId;
    private final DomainIdentifier producerPartnerId;
    private final AssetLifecycleStatus lifecycleStatus;
    private final AssetCustodian custodian;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private Asset(
            DomainIdentifier id, DomainIdentifier rsotObjectId, AssetType assetType,
            DomainIdentifier owningOrganizationId, DomainIdentifier owningSubdivisionId,
            LocalDate acquisitionDate, AssetValue acquisitionValue, DomainIdentifier acquiredFromPartnerId,
            DomainIdentifier producerPartnerId, AssetLifecycleStatus lifecycleStatus, AssetCustodian custodian, long version,
            Instant createdAt, Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy,
            String lastReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.rsotObjectId = Objects.requireNonNull(rsotObjectId, "rsotObjectId");
        this.assetType = Objects.requireNonNull(assetType, "assetType");
        this.owningOrganizationId = Objects.requireNonNull(owningOrganizationId, "owningOrganizationId");
        this.owningSubdivisionId = owningSubdivisionId;
        this.acquisitionDate = Objects.requireNonNull(acquisitionDate, "acquisitionDate");
        this.acquisitionValue = Objects.requireNonNull(acquisitionValue, "acquisitionValue");
        this.acquiredFromPartnerId = acquiredFromPartnerId;
        this.producerPartnerId = producerPartnerId;
        this.lifecycleStatus = Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        this.custodian = Objects.requireNonNull(custodian, "custodian");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = text(lastReason, "lastReason", 2, 1024);
    }

    /**
     * Backward-compatible E02 factory for assets whose canonical manufacturer/publisher is not known yet.
     * Such assets remain non-operational until the governed producer correction is completed.
     */
    public static Asset acquired(
            DomainIdentifier id, DomainIdentifier rsotObjectId, AssetType assetType,
            DomainIdentifier owningOrganizationId, DomainIdentifier owningSubdivisionId,
            LocalDate acquisitionDate, AssetValue acquisitionValue, DomainIdentifier acquiredFromPartnerId,
            DomainIdentifier actorId, String reason, Instant now) {
        return acquired(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId, acquisitionDate,
                acquisitionValue, acquiredFromPartnerId, null, actorId, reason, now);
    }

    public static Asset acquired(
            DomainIdentifier id, DomainIdentifier rsotObjectId, AssetType assetType,
            DomainIdentifier owningOrganizationId, DomainIdentifier owningSubdivisionId,
            LocalDate acquisitionDate, AssetValue acquisitionValue, DomainIdentifier acquiredFromPartnerId,
            DomainIdentifier producerPartnerId, DomainIdentifier actorId, String reason, Instant now) {
        return new Asset(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId,
                acquisitionDate, acquisitionValue, acquiredFromPartnerId, producerPartnerId, AssetLifecycleStatus.ACQUIRED,
                AssetCustodian.organization(owningOrganizationId), 1, now, now, actorId, actorId, reason);
    }

    /** Backward-compatible persistence factory for pre-E03 rows without a canonical producer. */
    public static Asset restore(
            DomainIdentifier id, DomainIdentifier rsotObjectId, AssetType assetType,
            DomainIdentifier owningOrganizationId, DomainIdentifier owningSubdivisionId,
            LocalDate acquisitionDate, AssetValue acquisitionValue, DomainIdentifier acquiredFromPartnerId,
            AssetLifecycleStatus lifecycleStatus, AssetCustodian custodian, long version,
            Instant createdAt, Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy,
            String lastReason) {
        return restore(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId, acquisitionDate,
                acquisitionValue, acquiredFromPartnerId, null, lifecycleStatus, custodian, version,
                createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    public static Asset restore(
            DomainIdentifier id, DomainIdentifier rsotObjectId, AssetType assetType,
            DomainIdentifier owningOrganizationId, DomainIdentifier owningSubdivisionId,
            LocalDate acquisitionDate, AssetValue acquisitionValue, DomainIdentifier acquiredFromPartnerId,
            DomainIdentifier producerPartnerId, AssetLifecycleStatus lifecycleStatus, AssetCustodian custodian, long version,
            Instant createdAt, Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy,
            String lastReason) {
        return new Asset(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId,
                acquisitionDate, acquisitionValue, acquiredFromPartnerId, producerPartnerId, lifecycleStatus, custodian, version,
                createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }


    /** Sets or corrects the governed manufacturer/publisher weak reference with optimistic versioning. */
    public Asset setProducer(DomainIdentifier producerId, DomainIdentifier actorId, String reason, Instant now) {
        Objects.requireNonNull(producerId, "producerId");
        if (lifecycleStatus == AssetLifecycleStatus.DISPOSED) {
            throw new AssetConflictException("ITAM_ASSET_STATE_CONFLICT", "disposed asset producer cannot change");
        }
        if (producerId.equals(producerPartnerId)) return this;
        return new Asset(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId, acquisitionDate,
                acquisitionValue, acquiredFromPartnerId, producerId, lifecycleStatus, custodian, Math.addExact(version, 1),
                createdAt, now, createdBy, actorId, text(reason, "reason", 2, 1024));
    }

    public Asset receive(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireStatus(AssetLifecycleStatus.ACQUIRED);
        return changed(AssetLifecycleStatus.RECEIVED, target, actorId, reason, now);
    }

    public Asset stock(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireOneOf(AssetLifecycleStatus.RECEIVED, AssetLifecycleStatus.RETURNED);
        requireCustodian(target, AssetCustodianKind.ORGANIZATION, AssetCustodianKind.SUBDIVISION);
        return changed(AssetLifecycleStatus.IN_STOCK, target, actorId, reason, now);
    }

    public Asset assign(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireOneOf(AssetLifecycleStatus.RECEIVED, AssetLifecycleStatus.IN_STOCK, AssetLifecycleStatus.RETURNED);
        if (target.kind() == AssetCustodianKind.NONE || target.kind() == AssetCustodianKind.PARTNER) {
            throw new AssetConflictException("ITAM_ASSET_CUSTODIAN_INVALID", "assignment requires an organization, subdivision or actor custodian");
        }
        return changed(AssetLifecycleStatus.ASSIGNED, target, actorId, reason, now);
    }

    public Asset deploy(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireStatus(AssetLifecycleStatus.ASSIGNED);
        if (target.kind() == AssetCustodianKind.NONE || target.kind() == AssetCustodianKind.PARTNER) {
            throw new AssetConflictException("ITAM_ASSET_CUSTODIAN_INVALID", "deployment requires an accountable internal custodian");
        }
        return changed(AssetLifecycleStatus.DEPLOYED, target, actorId, reason, now);
    }

    public Asset transfer(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireOneOf(AssetLifecycleStatus.RECEIVED, AssetLifecycleStatus.IN_STOCK, AssetLifecycleStatus.ASSIGNED,
                AssetLifecycleStatus.DEPLOYED, AssetLifecycleStatus.MAINTENANCE, AssetLifecycleStatus.RETURNED);
        if (target.kind() == AssetCustodianKind.NONE) {
            throw new AssetConflictException("ITAM_ASSET_CUSTODIAN_INVALID", "transfer requires a target custodian");
        }
        return changed(lifecycleStatus, target, actorId, reason, now);
    }

    public Asset startMaintenance(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireOneOf(AssetLifecycleStatus.RECEIVED, AssetLifecycleStatus.IN_STOCK, AssetLifecycleStatus.ASSIGNED,
                AssetLifecycleStatus.DEPLOYED, AssetLifecycleStatus.RETURNED);
        if (target.kind() == AssetCustodianKind.NONE || target.kind() == AssetCustodianKind.ACTOR) {
            throw new AssetConflictException("ITAM_ASSET_CUSTODIAN_INVALID", "maintenance requires an organization, subdivision or partner custodian");
        }
        return changed(AssetLifecycleStatus.MAINTENANCE, target, actorId, reason, now);
    }

    public Asset returnFromMaintenance(AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        requireStatus(AssetLifecycleStatus.MAINTENANCE);
        requireCustodian(target, AssetCustodianKind.ORGANIZATION, AssetCustodianKind.SUBDIVISION);
        return changed(AssetLifecycleStatus.RETURNED, target, actorId, reason, now);
    }

    public Asset retire(DomainIdentifier actorId, String reason, Instant now) {
        requireOneOf(AssetLifecycleStatus.RECEIVED, AssetLifecycleStatus.IN_STOCK, AssetLifecycleStatus.ASSIGNED,
                AssetLifecycleStatus.DEPLOYED, AssetLifecycleStatus.MAINTENANCE, AssetLifecycleStatus.RETURNED);
        return changed(AssetLifecycleStatus.RETIRED, custodian, actorId, reason, now);
    }

    public Asset dispose(DomainIdentifier actorId, String reason, Instant now) {
        requireStatus(AssetLifecycleStatus.RETIRED);
        return changed(AssetLifecycleStatus.DISPOSED, AssetCustodian.none(), actorId, reason, now);
    }

    private Asset changed(
            AssetLifecycleStatus status, AssetCustodian target, DomainIdentifier actorId, String reason, Instant now) {
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(now, "now");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("transition time precedes current state");
        return new Asset(id, rsotObjectId, assetType, owningOrganizationId, owningSubdivisionId,
                acquisitionDate, acquisitionValue, acquiredFromPartnerId, producerPartnerId, status, target, Math.addExact(version, 1),
                createdAt, now, createdBy, actorId, text(reason, "reason", 2, 1024));
    }

    private void requireStatus(AssetLifecycleStatus expected) {
        if (lifecycleStatus != expected) {
            throw new AssetConflictException("ITAM_ASSET_STATE_CONFLICT",
                    "asset cannot transition from " + lifecycleStatus.wireValue() + " in this operation");
        }
    }

    private void requireOneOf(AssetLifecycleStatus... allowed) {
        for (AssetLifecycleStatus candidate : allowed) if (lifecycleStatus == candidate) return;
        throw new AssetConflictException("ITAM_ASSET_STATE_CONFLICT",
                "asset cannot transition from " + lifecycleStatus.wireValue() + " in this operation");
    }

    private static void requireCustodian(AssetCustodian target, AssetCustodianKind... allowed) {
        for (AssetCustodianKind candidate : allowed) if (target.kind() == candidate) return;
        throw new AssetConflictException("ITAM_ASSET_CUSTODIAN_INVALID", "custodian kind is invalid for this transition");
    }

    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        String normalized = value.strip();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    public DomainIdentifier id() { return id; }
    public DomainIdentifier rsotObjectId() { return rsotObjectId; }
    public AssetType assetType() { return assetType; }
    public DomainIdentifier owningOrganizationId() { return owningOrganizationId; }
    public DomainIdentifier owningSubdivisionId() { return owningSubdivisionId; }
    public LocalDate acquisitionDate() { return acquisitionDate; }
    public AssetValue acquisitionValue() { return acquisitionValue; }
    public DomainIdentifier acquiredFromPartnerId() { return acquiredFromPartnerId; }
    /** Canonical manufacturer for hardware or software publisher for software; null only for legacy/incomplete assets. */
    public DomainIdentifier producerPartnerId() { return producerPartnerId; }
    public AssetLifecycleStatus lifecycleStatus() { return lifecycleStatus; }
    public AssetCustodian custodian() { return custodian; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public DomainIdentifier createdBy() { return createdBy; }
    public DomainIdentifier updatedBy() { return updatedBy; }
    public String lastReason() { return lastReason; }
}
