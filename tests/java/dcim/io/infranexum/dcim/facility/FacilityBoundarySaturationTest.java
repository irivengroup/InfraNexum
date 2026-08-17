package io.infranexum.dcim.facility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exhaustive branch regression for facility shape, scalar bounds and lifecycle transitions. */
final class FacilityBoundarySaturationTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ID = id(1);
    private static final DomainIdentifier ORG = id(2);
    private static final DomainIdentifier SUB = id(3);
    private static final DomainIdentifier PARENT = id(4);
    private static final DomainIdentifier ACTOR = id(5);

    @Test
    void siteLifecycleCoversEveryAllowedAndRejectedTransitionOperand() {
        FacilityNode draft = site();
        assertInvalid(draft, FacilityStatus.DRAFT);
        assertInvalid(draft, FacilityStatus.SUSPENDED);
        assertInvalid(draft, FacilityStatus.ARCHIVED);
        FacilityNode active = draft.changeStatus(FacilityStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(1));
        FacilityNode suspended = active.changeStatus(FacilityStatus.SUSPENDED, ACTOR, "suspend", NOW.plusSeconds(2));
        assertEquals(FacilityStatus.ACTIVE, suspended.changeStatus(FacilityStatus.ACTIVE, ACTOR, "resume", NOW.plusSeconds(3)).status());
        FacilityNode archivedFromActive = active.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(3));
        assertEquals(FacilityStatus.DELETED, archivedFromActive.changeStatus(FacilityStatus.DELETED, ACTOR, "delete", NOW.plusSeconds(4)).status());
        FacilityNode archivedFromSuspended = suspended.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(4));
        assertEquals(FacilityStatus.ARCHIVED, archivedFromSuspended.status());
        assertInvalid(active, FacilityStatus.LOCKED);
        assertInvalid(suspended, FacilityStatus.MAINTENANCE);
    }

    @Test
    void buildingAndFloorLifecycleExerciseMaintenanceAndArchivePaths() {
        for (FacilityKind kind : new FacilityKind[] {FacilityKind.BUILDING, FacilityKind.FLOOR}) {
            FacilityNode draft = node(kind);
            FacilityNode active = draft.changeStatus(FacilityStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(1));
            FacilityNode maintenance = active.changeStatus(FacilityStatus.MAINTENANCE, ACTOR, "maintain", NOW.plusSeconds(2));
            assertEquals(FacilityStatus.ACTIVE, maintenance.changeStatus(FacilityStatus.ACTIVE, ACTOR, "restore", NOW.plusSeconds(3)).status());
            FacilityNode archived = active.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(3));
            assertEquals(FacilityStatus.DELETED, archived.changeStatus(FacilityStatus.DELETED, ACTOR, "delete", NOW.plusSeconds(4)).status());
            assertInvalid(draft, FacilityStatus.MAINTENANCE);
            assertInvalid(maintenance, FacilityStatus.ARCHIVED);
        }
    }

    @Test
    void roomLifecycleCoversMaintenanceLockArchiveAndUnlockPaths() {
        FacilityNode draft = node(FacilityKind.ROOM);
        FacilityNode active = draft.changeStatus(FacilityStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(1));
        FacilityNode maintenance = active.changeStatus(FacilityStatus.MAINTENANCE, ACTOR, "maintain", NOW.plusSeconds(2));
        assertEquals(FacilityStatus.ACTIVE, maintenance.changeStatus(FacilityStatus.ACTIVE, ACTOR, "restore", NOW.plusSeconds(3)).status());
        FacilityNode locked = active.changeStatus(FacilityStatus.LOCKED, ACTOR, "lock", NOW.plusSeconds(2));
        assertEquals(FacilityStatus.ACTIVE, locked.changeStatus(FacilityStatus.ACTIVE, ACTOR, "unlock", NOW.plusSeconds(3)).status());
        FacilityNode archived = active.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(3));
        assertEquals(FacilityStatus.DELETED, archived.changeStatus(FacilityStatus.DELETED, ACTOR, "delete", NOW.plusSeconds(4)).status());
        assertInvalid(locked, FacilityStatus.ARCHIVED);
    }

    @Test
    void zoneLifecycleCoversMaintenanceInactiveArchiveAndDeletePaths() {
        FacilityNode draft = node(FacilityKind.ZONE);
        FacilityNode active = draft.changeStatus(FacilityStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(1));
        FacilityNode maintenance = active.changeStatus(FacilityStatus.MAINTENANCE, ACTOR, "maintain", NOW.plusSeconds(2));
        assertEquals(FacilityStatus.ACTIVE, maintenance.changeStatus(FacilityStatus.ACTIVE, ACTOR, "restore", NOW.plusSeconds(3)).status());
        FacilityNode inactive = active.changeStatus(FacilityStatus.INACTIVE, ACTOR, "disable", NOW.plusSeconds(2));
        FacilityNode archived = inactive.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(3));
        assertEquals(FacilityStatus.DELETED, archived.changeStatus(FacilityStatus.DELETED, ACTOR, "delete", NOW.plusSeconds(4)).status());
        assertInvalid(active, FacilityStatus.ARCHIVED);
        assertInvalid(inactive, FacilityStatus.ACTIVE);
    }

    @Test
    void statusValidationRejectsStatusValuesIllegalForEachKind() {
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.SITE, FacilityStatus.MAINTENANCE));
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.SITE, FacilityStatus.LOCKED));
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.BUILDING, FacilityStatus.SUSPENDED));
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.FLOOR, FacilityStatus.LOCKED));
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.ROOM, FacilityStatus.SUSPENDED));
        assertThrows(IllegalArgumentException.class, () -> restored(FacilityKind.ZONE, FacilityStatus.LOCKED));
    }

    @Test
    void siteScalarBoundsExerciseBothSidesOfEveryCompoundRange() {
        assertEquals(new BigDecimal("-90"), siteWithGeo(new BigDecimal("-90"), new BigDecimal("-180")).latitude());
        assertEquals(new BigDecimal("90"), siteWithGeo(new BigDecimal("90"), new BigDecimal("180")).latitude());
        assertThrows(IllegalArgumentException.class, () -> siteWithGeo(new BigDecimal("-90.1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> siteWithGeo(new BigDecimal("90.1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> siteWithGeo(BigDecimal.ZERO, new BigDecimal("-180.1")));
        assertThrows(IllegalArgumentException.class, () -> siteWithGeo(BigDecimal.ZERO, new BigDecimal("180.1")));
        assertNull(siteWithGeo(null, null).latitude());

        assertThrows(IllegalArgumentException.class, () -> siteWith("AA", "Europe/Paris", "Paris", "75001", "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("F", "Europe/Paris", "Paris", "75001", "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("FRA", "Europe/Paris", "Paris", "75001", "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("FR", "x".repeat(65), "Paris", "75001", "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("FR", "Europe/Paris", "x".repeat(65), "75001", "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("FR", "Europe/Paris", "Paris", "x".repeat(17), "10 rue", null));
        assertThrows(IllegalArgumentException.class, () -> siteWith("FR", "Europe/Paris", "Paris", "75001", "x".repeat(129), null));
        assertEquals("unit", siteWith("FR", "Europe/Paris", "Paris", "75001", "10 rue", " unit ").addressLine2());
    }

    @Test
    void kindSpecificNumericFieldsCoverRequiredOptionalPositiveAndForbiddenBranches() {
        FacilityNode building = building(1, BigDecimal.ONE);
        assertEquals(1, building.floorCount());
        assertThrows(IllegalArgumentException.class, () -> building(0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> building(-1, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> building(null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> building(1, BigDecimal.ZERO));
        assertNull(building(1, null).areaM2());

        FacilityNode floor = floor(0, null, null, null);
        assertEquals(0, floor.levelNumber());
        assertNull(floor.levelHeightM());
        assertNull(floor.capacityKw());
        assertThrows(NullPointerException.class, () -> floor(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> floor(1, BigDecimal.ZERO, null, null));
        assertThrows(IllegalArgumentException.class, () -> floor(1, null, BigDecimal.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> floor(1, null, null, BigDecimal.ZERO));

        FacilityNode room = room(BigDecimal.ONE, null, null);
        assertEquals(BigDecimal.ONE, room.areaM2());
        assertThrows(IllegalArgumentException.class, () -> room(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> room(BigDecimal.ZERO, null, null));
        assertThrows(IllegalArgumentException.class, () -> room(BigDecimal.ONE, BigDecimal.ZERO, null));
        assertNull(room(BigDecimal.ONE, null, null).capacityKw());
    }

    @Test
    void optionalTextAndKindRestrictedFieldsCoverNullBlankInvalidAndMaximumPaths() {
        assertNull(room(BigDecimal.ONE, null, " ").accessRestriction());
        assertEquals("open", room(BigDecimal.ONE, null, " OPEN ").accessRestriction());
        assertThrows(IllegalArgumentException.class, () -> building(1, BigDecimal.ONE).updateMetadata(
                "Building", null, null, null, null, null, null, null, null, 1, null, BigDecimal.ONE,
                null, null, "open", null, null, ACTOR, "update", NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> zone(null));
        assertThrows(IllegalArgumentException.class, () -> zone(" "));
        for (String allowed : new String[] {"cooling", "power_distribution", "airflow", "security"}) {
            assertEquals(allowed, zone(allowed).zoneType());
        }
        assertThrows(IllegalArgumentException.class, () -> zone("other"));
        assertEquals(4096, nodeWithDescription(FacilityKind.ROOM, "x".repeat(4096)).description().length());
        assertThrows(IllegalArgumentException.class, () -> nodeWithDescription(FacilityKind.ROOM, "x".repeat(4097)));
        assertEquals(2000, nodeWithDescription(FacilityKind.SITE, "x".repeat(2000)).description().length());
        assertThrows(IllegalArgumentException.class, () -> nodeWithDescription(FacilityKind.SITE, "x".repeat(2001)));
    }

    @Test
    void temporalAndReadOnlyGuardsCoverDeletedAndBackwardClockBranches() {
        FacilityNode active = node(FacilityKind.ROOM).changeStatus(FacilityStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> active.updateMetadata(
                "Room", null, null, null, null, null, null, null, null, null, null, BigDecimal.ONE,
                null, null, "secure", null, null, ACTOR, "update", NOW));
        FacilityNode archived = active.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(2));
        FacilityNode deleted = archived.changeStatus(FacilityStatus.DELETED, ACTOR, "delete", NOW.plusSeconds(3));
        assertThrows(FacilityConflictException.class, () -> deleted.updateMetadata(
                "Room", null, null, null, null, null, null, null, null, null, null, BigDecimal.ONE,
                null, null, "secure", null, null, ACTOR, "update", NOW.plusSeconds(4)));
        assertThrows(NullPointerException.class, () -> active.changeStatus(FacilityStatus.LOCKED, ACTOR, "lock", null));
    }


    @Test
    void parsingRestoreFilteringAndContextBoundariesCoverRemainingScalarBranches() {
        assertThrows(IllegalArgumentException.class, () -> FacilityKind.parse(null));
        assertThrows(IllegalArgumentException.class, () -> FacilityKind.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> FacilityStatus.parse(null));
        assertThrows(IllegalArgumentException.class, () -> FacilityStatus.parse(" "));

        FacilityNode base = site();
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.restore(
                base.id(), base.kind(), base.organizationId(), base.subdivisionId(), base.parentId(), base.scopeId(),
                base.code(), base.displayName(), base.status(), base.addressLine1(), base.addressLine2(), base.postalCode(), base.city(),
                base.countryCode(), base.timezone(), base.latitude(), base.longitude(), base.floorCount(), base.levelNumber(), base.areaM2(),
                base.levelHeightM(), base.capacityKw(), base.accessRestriction(), base.zoneType(), base.description(), 0, base.createdAt(),
                base.updatedAt(), base.createdBy(), base.updatedBy(), base.lastReason()));
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.restore(
                base.id(), base.kind(), base.organizationId(), base.subdivisionId(), base.parentId(), base.scopeId(),
                base.code(), base.displayName(), base.status(), base.addressLine1(), base.addressLine2(), base.postalCode(), base.city(),
                base.countryCode(), base.timezone(), base.latitude(), base.longitude(), base.floorCount(), base.levelNumber(), base.areaM2(),
                base.levelHeightM(), base.capacityKw(), base.accessRestriction(), base.zoneType(), base.description(), 1, NOW,
                NOW.minusSeconds(1), base.createdBy(), base.updatedBy(), base.lastReason()));
        assertThrows(IllegalArgumentException.class, () -> nodeWithDescription(FacilityKind.ROOM, "bad\n"));
        assertNull(room(BigDecimal.ONE, null, " ").zoneType());
        assertNull(FacilityNode.draft(ID, FacilityKind.BUILDING, ORG, SUB, PARENT, PARENT, new FacilityCode("BLD2"), "Building",
                " ", " ", " ", " ", " ", " ", null, null, 1, null, BigDecimal.ONE, null, null,
                null, " ", null, ACTOR, "create", NOW).addressLine1());
        assertNull(siteWith("FR", "Europe/Paris", "Paris", "75001", "10 rue", " ").addressLine2());
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.draft(ID, FacilityKind.ROOM, ORG, SUB, PARENT, PARENT,
                new FacilityCode("ROOM2"), "Room", null, null, null, null, null, null, null, null, 1, null,
                BigDecimal.ONE, null, null, "secure", null, null, ACTOR, "create", NOW));
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.draft(ID, FacilityKind.ROOM, ORG, SUB, PARENT, PARENT,
                new FacilityCode("ROOM3"), "Room", null, null, null, null, null, null, null, null, null, null,
                BigDecimal.ONE, null, null, "secure", "cooling", null, ACTOR, "create", NOW));

        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, null, null, null, 0));
        assertNull(new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, null, null, null, null, null, 1).countryCode());
        assertNull(new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, null, null, null, " ", null, 1).countryCode());
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, null, "F", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, null, "ZZ", null, 1));

        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilityCommandContext(ACTOR, ID, "1234567", "ok"));
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilityCommandContext(ACTOR, ID, "x".repeat(201), "ok"));
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilityCommandContext(ACTOR, ID, "12345678", "x"));
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilityCommandContext(ACTOR, ID, "12345678", "x".repeat(1025)));
    }

    private static void assertInvalid(FacilityNode node, FacilityStatus target) {
        assertThrows(FacilityConflictException.class,
                () -> node.changeStatus(target, ACTOR, "invalid", node.updatedAt().plusSeconds(1)));
    }

    private static FacilityNode site() { return siteWith("FR", "Europe/Paris", "Paris", "75001", "10 rue", null); }

    private static FacilityNode siteWith(String country, String timezone, String city, String postal, String line1, String line2) {
        return FacilityNode.draft(ID, FacilityKind.SITE, ORG, SUB, null, SUB, new FacilityCode("SITE1"), "Primary site",
                line1, line2, postal, city, country, timezone, null, null, null, null, null, null, null,
                null, null, null, ACTOR, "create", NOW);
    }

    private static FacilityNode siteWithGeo(BigDecimal lat, BigDecimal lon) {
        return FacilityNode.draft(ID, FacilityKind.SITE, ORG, SUB, null, SUB, new FacilityCode("SITE1"), "Primary site",
                "10 rue", null, "75001", "Paris", "FR", "Europe/Paris", lat, lon, null, null, null, null, null,
                null, null, null, ACTOR, "create", NOW);
    }

    private static FacilityNode building(Integer floors, BigDecimal area) {
        return FacilityNode.draft(ID, FacilityKind.BUILDING, ORG, SUB, PARENT, PARENT, new FacilityCode("BLD1"), "Building",
                null, null, null, null, null, null, null, null, floors, null, area, null, null,
                null, null, null, ACTOR, "create", NOW);
    }

    private static FacilityNode floor(Integer level, BigDecimal area, BigDecimal height, BigDecimal capacity) {
        return FacilityNode.draft(ID, FacilityKind.FLOOR, ORG, SUB, PARENT, PARENT, new FacilityCode("FLR1"), "Floor",
                null, null, null, null, null, null, null, null, null, level, area, height, capacity,
                null, null, null, ACTOR, "create", NOW);
    }

    private static FacilityNode room(BigDecimal area, BigDecimal capacity, String access) {
        return FacilityNode.draft(ID, FacilityKind.ROOM, ORG, SUB, PARENT, PARENT, new FacilityCode("ROOM1"), "Room",
                null, null, null, null, null, null, null, null, null, null, area, null, capacity,
                access, null, null, ACTOR, "create", NOW);
    }

    private static FacilityNode zone(String zoneType) {
        return FacilityNode.draft(ID, FacilityKind.ZONE, ORG, SUB, PARENT, PARENT, new FacilityCode("ZONE1"), "Zone",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, zoneType, null, ACTOR, "create", NOW);
    }

    private static FacilityNode node(FacilityKind kind) {
        return switch (kind) {
            case SITE -> site();
            case BUILDING -> building(1, BigDecimal.ONE);
            case FLOOR -> floor(0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
            case ROOM -> room(BigDecimal.ONE, BigDecimal.ONE, "secure");
            case ZONE -> zone("cooling");
        };
    }

    private static FacilityNode restored(FacilityKind kind, FacilityStatus status) {
        FacilityNode base = node(kind);
        return FacilityNode.restore(base.id(), base.kind(), base.organizationId(), base.subdivisionId(), base.parentId(), base.scopeId(),
                base.code(), base.displayName(), status, base.addressLine1(), base.addressLine2(), base.postalCode(), base.city(),
                base.countryCode(), base.timezone(), base.latitude(), base.longitude(), base.floorCount(), base.levelNumber(), base.areaM2(),
                base.levelHeightM(), base.capacityKw(), base.accessRestriction(), base.zoneType(), base.description(), base.version(), base.createdAt(),
                base.updatedAt(), base.createdBy(), base.updatedBy(), base.lastReason());
    }

    private static FacilityNode nodeWithDescription(FacilityKind kind, String description) {
        if (kind == FacilityKind.SITE) {
            return FacilityNode.draft(ID, kind, ORG, SUB, null, SUB, new FacilityCode("SITE1"), "Primary site",
                    "10 rue", null, "75001", "Paris", "FR", "Europe/Paris", null, null, null, null, null, null, null,
                    null, null, description, ACTOR, "create", NOW);
        }
        return FacilityNode.draft(ID, kind, ORG, SUB, PARENT, PARENT, new FacilityCode("NODE1"), "Facility node",
                null, null, null, null, null, null, null, null,
                kind == FacilityKind.BUILDING ? 1 : null, kind == FacilityKind.FLOOR ? 0 : null,
                kind == FacilityKind.ZONE ? null : BigDecimal.ONE,
                kind == FacilityKind.FLOOR ? BigDecimal.ONE : null,
                kind == FacilityKind.FLOOR || kind == FacilityKind.ROOM ? BigDecimal.ONE : null,
                kind == FacilityKind.ROOM ? "secure" : null, kind == FacilityKind.ZONE ? "cooling" : null,
                description, ACTOR, "create", NOW);
    }

    private static DomainIdentifier id(long n) {
        return new DomainIdentifier(new UUID(0x0198000000007000L + n, 0x8000000000000000L + n));
    }
}
