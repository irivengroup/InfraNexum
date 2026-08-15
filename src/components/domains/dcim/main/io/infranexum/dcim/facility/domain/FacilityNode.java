package io.infranexum.dcim.facility.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative DCIM physical hierarchy node for PGM-07-E04.
 *
 * <p>The aggregate owns hierarchy metadata and lifecycle while Organization/Subdivision
 * identifiers remain weak cross-context references. Parentage is immutable in E04 so
 * moving a physical object cannot silently rewrite topology history.</p>
 */
public final class FacilityNode {
    private static final Set<String> ACCESS = Set.of("open", "restricted", "secure");
    private static final Set<String> ZONE_TYPES = Set.of("cooling", "power_distribution", "airflow", "security");

    private final DomainIdentifier id;
    private final FacilityKind kind;
    private final DomainIdentifier organizationId;
    private final DomainIdentifier subdivisionId;
    private final DomainIdentifier parentId;
    private final DomainIdentifier scopeId;
    private final FacilityCode code;
    private final String displayName;
    private final FacilityStatus status;
    private final String addressLine1;
    private final String addressLine2;
    private final String postalCode;
    private final String city;
    private final String countryCode;
    private final String timezone;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final Integer floorCount;
    private final Integer levelNumber;
    private final BigDecimal areaM2;
    private final BigDecimal levelHeightM;
    private final BigDecimal capacityKw;
    private final String accessRestriction;
    private final String zoneType;
    private final String description;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private FacilityNode(
            DomainIdentifier id, FacilityKind kind, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier parentId, DomainIdentifier scopeId, FacilityCode code, String displayName,
            FacilityStatus status, String addressLine1, String addressLine2, String postalCode, String city,
            String countryCode, String timezone, BigDecimal latitude, BigDecimal longitude,
            Integer floorCount, Integer levelNumber, BigDecimal areaM2, BigDecimal levelHeightM, BigDecimal capacityKw,
            String accessRestriction, String zoneType, String description, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.subdivisionId = Objects.requireNonNull(subdivisionId, "subdivisionId");
        this.parentId = parentId;
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.code = Objects.requireNonNull(code, "code");
        this.displayName = text(displayName, "displayName", 3, 128);
        this.status = validateStatus(kind, Objects.requireNonNull(status, "status"));
        this.addressLine1 = siteText(kind, addressLine1, "addressLine1", 1, 128, true);
        this.addressLine2 = siteText(kind, addressLine2, "addressLine2", 1, 128, false);
        this.postalCode = siteText(kind, postalCode, "postalCode", 1, 16, true);
        this.city = siteText(kind, city, "city", 1, 64, true);
        this.countryCode = kind == FacilityKind.SITE ? country(countryCode) : absentOutsideSite(countryCode, "countryCode");
        this.timezone = kind == FacilityKind.SITE ? timezone(timezone) : absentOutsideSite(timezone, "timezone");
        this.latitude = geo(kind, latitude, "latitude", new BigDecimal("-90"), new BigDecimal("90"));
        this.longitude = geo(kind, longitude, "longitude", new BigDecimal("-180"), new BigDecimal("180"));
        this.floorCount = kind == FacilityKind.BUILDING ? positive(floorCount, "floorCount", true) : absentInteger(floorCount, "floorCount");
        this.levelNumber = kind == FacilityKind.FLOOR ? Objects.requireNonNull(levelNumber, "levelNumber") : absentInteger(levelNumber, "levelNumber");
        this.areaM2 = area(kind, areaM2);
        this.levelHeightM = kind == FacilityKind.FLOOR ? positive(levelHeightM, "levelHeightM", false) : absentDecimal(levelHeightM, "levelHeightM");
        this.capacityKw = (kind == FacilityKind.FLOOR || kind == FacilityKind.ROOM)
                ? positive(capacityKw, "capacityKw", false) : absentDecimal(capacityKw, "capacityKw");
        this.accessRestriction = validateAccess(kind, accessRestriction);
        this.zoneType = validateZoneType(kind, zoneType);
        this.description = nullableText(description, "description", 1, kind == FacilityKind.SITE ? 2000 : 4096);
        this.version = version;
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = text(lastReason, "lastReason", 2, 1024);
        validateParent(kind, parentId);
    }

    public static FacilityNode draft(
            DomainIdentifier id, FacilityKind kind, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier parentId, DomainIdentifier scopeId, FacilityCode code, String displayName,
            String addressLine1, String addressLine2, String postalCode, String city, String countryCode, String timezone,
            BigDecimal latitude, BigDecimal longitude, Integer floorCount,
            Integer levelNumber, BigDecimal areaM2, BigDecimal levelHeightM, BigDecimal capacityKw,
            String accessRestriction, String zoneType, String description, DomainIdentifier actorId, String reason, Instant now) {
        return new FacilityNode(id, kind, organizationId, subdivisionId, parentId, scopeId, code, displayName,
                FacilityStatus.DRAFT, addressLine1, addressLine2, postalCode, city, countryCode, timezone, latitude, longitude, floorCount, levelNumber, areaM2,
                levelHeightM, capacityKw, accessRestriction, zoneType, description, 1, now, now, actorId, actorId, reason);
    }

    public static FacilityNode restore(
            DomainIdentifier id, FacilityKind kind, DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier parentId, DomainIdentifier scopeId, FacilityCode code, String displayName,
            FacilityStatus status, String addressLine1, String addressLine2, String postalCode, String city,
            String countryCode, String timezone, BigDecimal latitude, BigDecimal longitude,
            Integer floorCount, Integer levelNumber, BigDecimal areaM2, BigDecimal levelHeightM, BigDecimal capacityKw,
            String accessRestriction, String zoneType, String description, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        return new FacilityNode(id, kind, organizationId, subdivisionId, parentId, scopeId, code, displayName, status,
                addressLine1, addressLine2, postalCode, city, countryCode, timezone, latitude, longitude, floorCount, levelNumber, areaM2, levelHeightM, capacityKw,
                accessRestriction, zoneType, description, version, createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    public FacilityNode updateMetadata(
            String newDisplayName, String newAddressLine1, String newAddressLine2, String newPostalCode, String newCity,
            String newCountryCode, String newTimezone, BigDecimal newLatitude,
            BigDecimal newLongitude, Integer newFloorCount, Integer newLevelNumber, BigDecimal newAreaM2,
            BigDecimal newLevelHeightM, BigDecimal newCapacityKw, String newAccessRestriction, String newZoneType,
            String newDescription, DomainIdentifier actorId, String reason, Instant now) {
        requireMutable();
        return new FacilityNode(id, kind, organizationId, subdivisionId, parentId, scopeId, code, newDisplayName, status,
                newAddressLine1, newAddressLine2, newPostalCode, newCity, newCountryCode, newTimezone, newLatitude, newLongitude, newFloorCount, newLevelNumber, newAreaM2,
                newLevelHeightM, newCapacityKw, newAccessRestriction, newZoneType, newDescription,
                Math.addExact(version, 1), createdAt, checkedNow(now), createdBy, actorId, reason);
    }

    public FacilityNode changeStatus(FacilityStatus target, DomainIdentifier actorId, String reason, Instant now) {
        Objects.requireNonNull(target, "target");
        if (!canTransition(status, target, kind)) {
            throw new FacilityConflictException("DCIM_STATUS_TRANSITION_INVALID",
                    kind.wireValue() + " cannot transition from " + status.wireValue() + " to " + target.wireValue());
        }
        return new FacilityNode(id, kind, organizationId, subdivisionId, parentId, scopeId, code, displayName, target,
                addressLine1, addressLine2, postalCode, city, countryCode, timezone, latitude, longitude, floorCount, levelNumber, areaM2, levelHeightM, capacityKw,
                accessRestriction, zoneType, description, Math.addExact(version, 1), createdAt, checkedNow(now), createdBy,
                Objects.requireNonNull(actorId, "actorId"), reason);
    }

    private void requireMutable() {
        if (status == FacilityStatus.ARCHIVED || status == FacilityStatus.DELETED) {
            throw new FacilityConflictException("DCIM_NODE_READ_ONLY", "archived or deleted facility is read-only");
        }
    }

    private Instant checkedNow(Instant now) {
        Objects.requireNonNull(now, "now");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("transition time precedes current state");
        return now;
    }

    private static boolean canTransition(FacilityStatus source, FacilityStatus target, FacilityKind kind) {
        if (source == target) return false;
        return switch (kind) {
            case SITE -> (source == FacilityStatus.DRAFT && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ACTIVE && (target == FacilityStatus.SUSPENDED || target == FacilityStatus.ARCHIVED))
                    || (source == FacilityStatus.SUSPENDED && (target == FacilityStatus.ACTIVE || target == FacilityStatus.ARCHIVED))
                    || (source == FacilityStatus.ARCHIVED && target == FacilityStatus.DELETED);
            case BUILDING, FLOOR -> (source == FacilityStatus.DRAFT && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ACTIVE && (target == FacilityStatus.MAINTENANCE || target == FacilityStatus.ARCHIVED))
                    || (source == FacilityStatus.MAINTENANCE && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ARCHIVED && target == FacilityStatus.DELETED);
            case ROOM -> (source == FacilityStatus.DRAFT && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ACTIVE && (target == FacilityStatus.MAINTENANCE || target == FacilityStatus.LOCKED || target == FacilityStatus.ARCHIVED))
                    || (source == FacilityStatus.MAINTENANCE && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.LOCKED && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ARCHIVED && target == FacilityStatus.DELETED);
            case ZONE -> (source == FacilityStatus.DRAFT && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.ACTIVE && (target == FacilityStatus.MAINTENANCE || target == FacilityStatus.INACTIVE))
                    || (source == FacilityStatus.MAINTENANCE && target == FacilityStatus.ACTIVE)
                    || (source == FacilityStatus.INACTIVE && target == FacilityStatus.ARCHIVED)
                    || (source == FacilityStatus.ARCHIVED && target == FacilityStatus.DELETED);
        };
    }

    private static FacilityStatus validateStatus(FacilityKind kind, FacilityStatus status) {
        Set<FacilityStatus> allowed = switch (kind) {
            case SITE -> Set.of(FacilityStatus.DRAFT, FacilityStatus.ACTIVE, FacilityStatus.SUSPENDED, FacilityStatus.ARCHIVED, FacilityStatus.DELETED);
            case BUILDING, FLOOR -> Set.of(FacilityStatus.DRAFT, FacilityStatus.ACTIVE, FacilityStatus.MAINTENANCE, FacilityStatus.ARCHIVED, FacilityStatus.DELETED);
            case ROOM -> Set.of(FacilityStatus.DRAFT, FacilityStatus.ACTIVE, FacilityStatus.MAINTENANCE, FacilityStatus.LOCKED, FacilityStatus.ARCHIVED, FacilityStatus.DELETED);
            case ZONE -> Set.of(FacilityStatus.DRAFT, FacilityStatus.ACTIVE, FacilityStatus.MAINTENANCE, FacilityStatus.INACTIVE, FacilityStatus.ARCHIVED, FacilityStatus.DELETED);
        };
        if (!allowed.contains(status)) throw new IllegalArgumentException("status is invalid for facility kind");
        return status;
    }

    private static void validateParent(FacilityKind kind, DomainIdentifier parentId) {
        if (kind == FacilityKind.SITE && parentId != null) throw new IllegalArgumentException("site parent is its subdivision scope, not a DCIM node");
        if (kind != FacilityKind.SITE && parentId == null) throw new IllegalArgumentException("facility parentId is required");
    }

    private static String validateAccess(FacilityKind kind, String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (kind != FacilityKind.ROOM || !ACCESS.contains(normalized)) throw new IllegalArgumentException("invalid accessRestriction");
        return normalized;
    }

    private static String validateZoneType(FacilityKind kind, String value) {
        if (kind != FacilityKind.ZONE) {
            if (value != null && !value.isBlank()) throw new IllegalArgumentException("zoneType is only valid for zone");
            return null;
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("zoneType is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!ZONE_TYPES.contains(normalized)) throw new IllegalArgumentException("invalid zoneType");
        return normalized;
    }

    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field); String result = value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        return result;
    }
    private static String nullableText(String value, String field, int min, int max) { return value == null || value.isBlank() ? null : text(value, field, min, max); }
    private static String siteText(FacilityKind kind, String value, String field, int min, int max, boolean required) {
        if (kind != FacilityKind.SITE) return absentOutsideSite(value, field);
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException(field + " is required for site");
            return null;
        }
        return text(value, field, min, max);
    }
    private static String absentOutsideSite(String value, String field) {
        if (value != null && !value.isBlank()) throw new IllegalArgumentException(field + " is only valid for site");
        return null;
    }
    private static String country(String value) {
        String result = text(value, "countryCode", 2, 2).toUpperCase(Locale.ROOT);
        if (!Set.of(Locale.getISOCountries()).contains(result)) throw new IllegalArgumentException("invalid countryCode");
        return result;
    }
    private static String timezone(String value) {
        String result = text(value, "timezone", 1, 64); ZoneId.of(result); return result;
    }
    private static BigDecimal geo(FacilityKind kind, BigDecimal value, String field, BigDecimal min, BigDecimal max) {
        if (kind != FacilityKind.SITE && kind != FacilityKind.BUILDING) return absentDecimal(value, field);
        return ranged(value, field, min, max);
    }
    private static BigDecimal area(FacilityKind kind, BigDecimal value) {
        if (kind != FacilityKind.BUILDING && kind != FacilityKind.FLOOR && kind != FacilityKind.ROOM) return absentDecimal(value, "areaM2");
        return positive(value, "areaM2", kind == FacilityKind.ROOM);
    }
    private static BigDecimal absentDecimal(BigDecimal value, String field) {
        if (value != null) throw new IllegalArgumentException(field + " is invalid for facility kind");
        return null;
    }
    private static Integer absentInteger(Integer value, String field) {
        if (value != null) throw new IllegalArgumentException(field + " is invalid for facility kind");
        return null;
    }
    private static BigDecimal ranged(BigDecimal value, String field, BigDecimal min, BigDecimal max) {
        if (value == null) return null; if (value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalArgumentException("invalid " + field); return value;
    }
    private static BigDecimal positive(BigDecimal value, String field, boolean required) {
        if (value == null) { if (required) throw new IllegalArgumentException(field + " is required"); return null; }
        if (value.signum() <= 0) throw new IllegalArgumentException(field + " must be positive"); return value;
    }
    private static Integer positive(Integer value, String field, boolean required) {
        if (value == null) { if (required) throw new IllegalArgumentException(field + " is required"); return null; }
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive"); return value;
    }

    public DomainIdentifier id() { return id; }
    public FacilityKind kind() { return kind; }
    public DomainIdentifier organizationId() { return organizationId; }
    public DomainIdentifier subdivisionId() { return subdivisionId; }
    public DomainIdentifier parentId() { return parentId; }
    public DomainIdentifier scopeId() { return scopeId; }
    public FacilityCode code() { return code; }
    public String displayName() { return displayName; }
    public FacilityStatus status() { return status; }
    public String addressLine1() { return addressLine1; }
    public String addressLine2() { return addressLine2; }
    public String postalCode() { return postalCode; }
    public String city() { return city; }
    public String countryCode() { return countryCode; }
    public String timezone() { return timezone; }
    public BigDecimal latitude() { return latitude; }
    public BigDecimal longitude() { return longitude; }
    public Integer floorCount() { return floorCount; }
    public Integer levelNumber() { return levelNumber; }
    public BigDecimal areaM2() { return areaM2; }
    public BigDecimal levelHeightM() { return levelHeightM; }
    public BigDecimal capacityKw() { return capacityKw; }
    public String accessRestriction() { return accessRestriction; }
    public String zoneType() { return zoneType; }
    public String description() { return description; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public DomainIdentifier createdBy() { return createdBy; }
    public DomainIdentifier updatedBy() { return updatedBy; }
    public String lastReason() { return lastReason; }
}
