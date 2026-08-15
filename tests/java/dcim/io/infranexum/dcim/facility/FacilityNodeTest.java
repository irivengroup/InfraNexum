package io.infranexum.dcim.facility;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.domain.FacilityCode;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exhaustive kind-specific invariant tests for the PGM-07-E04 physical hierarchy aggregate. */
final class FacilityNodeTest {
    private static final DomainIdentifier ID = id("01900000-0000-7000-8000-000000000001");
    private static final DomainIdentifier ORG = id("01900000-0000-7000-8000-000000000002");
    private static final DomainIdentifier SUB = id("01900000-0000-7000-8000-000000000003");
    private static final DomainIdentifier PARENT = id("01900000-0000-7000-8000-000000000004");
    private static final DomainIdentifier ACTOR = id("01900000-0000-7000-8000-000000000005");
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void siteRequiresStructuredAddressAndNormalizesGeography() {
        FacilityNode site = site("fr", "Europe/Paris", "Primary site");
        assertAll(
                () -> assertEquals(FacilityKind.SITE, site.kind()),
                () -> assertEquals("10 Rue de Rivoli", site.addressLine1()),
                () -> assertEquals("75001", site.postalCode()),
                () -> assertEquals("Paris", site.city()),
                () -> assertEquals("FR", site.countryCode()),
                () -> assertEquals("Europe/Paris", site.timezone()),
                () -> assertEquals(new BigDecimal("48.8566000"), site.latitude()),
                () -> assertEquals(new BigDecimal("2.3522000"), site.longitude()),
                () -> assertEquals(SUB, site.scopeId()));

        assertThrows(IllegalArgumentException.class, () -> siteWithAddress(null, null, "75001", "Paris", "FR", "Europe/Paris", "valid"));
        assertThrows(IllegalArgumentException.class, () -> siteWithAddress("10 Rue", null, null, "Paris", "FR", "Europe/Paris", "valid"));
        assertThrows(IllegalArgumentException.class, () -> siteWithAddress("10 Rue", null, "75001", null, "FR", "Europe/Paris", "valid"));
        assertThrows(IllegalArgumentException.class, () -> site("ZZ", "Europe/Paris", "valid"));
        assertThrows(java.time.DateTimeException.class, () -> site("FR", "Not/AZone", "valid"));
        assertThrows(IllegalArgumentException.class, () -> site("FR", "Europe/Paris", "x".repeat(2001)));
    }

    @Test
    void buildingFloorRoomAndZoneAcceptOnlyTheirOwnFields() {
        FacilityNode building = draft(FacilityKind.BUILDING, PARENT, PARENT, null, null, null, null, null, null,
                new BigDecimal("48.85"), new BigDecimal("2.35"), 4, null, new BigDecimal("1200"), null, null, null, null);
        assertEquals(4, building.floorCount());
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.BUILDING, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.BUILDING, PARENT, PARENT, null, null, null, null, null, null,
                null, null, 2, null, null, null, new BigDecimal("10"), null, null));

        FacilityNode floor = draft(FacilityKind.FLOOR, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, -1, new BigDecimal("300"), new BigDecimal("3.5"), new BigDecimal("180"), null, null);
        assertEquals(-1, floor.levelNumber());
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.FLOOR, PARENT, PARENT, null, null, null, null, null, null,
                new BigDecimal("48"), null, null, 1, null, null, null, null, null));

        FacilityNode room = draft(FacilityKind.ROOM, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("80"), null, new BigDecimal("120"), " SECURE ", null);
        assertEquals("secure", room.accessRestriction());
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.ROOM, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "open", null));
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.ROOM, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, BigDecimal.ONE, null, null, "public", null));

        FacilityNode zone = draft(FacilityKind.ZONE, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, " COOLING ");
        assertEquals("cooling", zone.zoneType());
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.ZONE, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, BigDecimal.ONE, null, "cooling"));
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.ZONE, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "unknown"));
    }

    @Test
    void parentAndSiteOnlyFieldsAreFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.draft(ID, FacilityKind.SITE, ORG, SUB, PARENT, SUB,
                new FacilityCode("PAR01"), "Paris", "10 Rue", null, "75001", "Paris", "FR", "Europe/Paris",
                null, null, null, null, null, null, null, null, null, null, ACTOR, "Create site", NOW));
        assertThrows(IllegalArgumentException.class, () -> draft(FacilityKind.ROOM, PARENT, PARENT, "10 Rue", null, "75001", "Paris", "FR", "Europe/Paris",
                null, null, null, null, BigDecimal.ONE, null, null, "secure", null));
        assertThrows(IllegalArgumentException.class, () -> FacilityNode.draft(ID, FacilityKind.ROOM, ORG, SUB, null, PARENT,
                new FacilityCode("ROOM1"), "Room one", null, null, null, null, null, null,
                null, null, null, null, BigDecimal.ONE, null, null, "secure", null, null, ACTOR, "Create room", NOW));
    }

    @Test
    void eachKindUsesItsNormativeLifecycleAndArchivedNodesAreReadOnly() {
        FacilityNode site = site("FR", "Europe/Paris", "Primary site").changeStatus(FacilityStatus.ACTIVE, ACTOR, "Activate", NOW.plusSeconds(1));
        site = site.changeStatus(FacilityStatus.SUSPENDED, ACTOR, "Suspend", NOW.plusSeconds(2));
        site = site.changeStatus(FacilityStatus.ARCHIVED, ACTOR, "Archive", NOW.plusSeconds(3));
        FacilityNode archivedSite = site;
        assertThrows(FacilityConflictException.class, () -> archivedSite.updateMetadata("Changed", "10 Rue", null, "75001", "Paris", "FR", "Europe/Paris",
                null, null, null, null, null, null, null, null, null, null, ACTOR, "No mutation", NOW.plusSeconds(4)));
        FacilityNode deletedSite = archivedSite.changeStatus(FacilityStatus.DELETED, ACTOR, "Delete", NOW.plusSeconds(4));
        assertEquals(FacilityStatus.DELETED, deletedSite.status());

        FacilityNode room = draft(FacilityKind.ROOM, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, BigDecimal.ONE, null, null, "secure", null)
                .changeStatus(FacilityStatus.ACTIVE, ACTOR, "Activate", NOW.plusSeconds(1))
                .changeStatus(FacilityStatus.LOCKED, ACTOR, "Lock", NOW.plusSeconds(2))
                .changeStatus(FacilityStatus.ACTIVE, ACTOR, "Unlock", NOW.plusSeconds(3));
        assertEquals(FacilityStatus.ACTIVE, room.status());

        FacilityNode zone = draft(FacilityKind.ZONE, PARENT, PARENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "airflow")
                .changeStatus(FacilityStatus.ACTIVE, ACTOR, "Activate", NOW.plusSeconds(1))
                .changeStatus(FacilityStatus.INACTIVE, ACTOR, "Disable", NOW.plusSeconds(2))
                .changeStatus(FacilityStatus.ARCHIVED, ACTOR, "Archive", NOW.plusSeconds(3));
        assertEquals(FacilityStatus.ARCHIVED, zone.status());
        assertCode("DCIM_STATUS_TRANSITION_INVALID", () -> room.changeStatus(FacilityStatus.SUSPENDED, ACTOR, "Invalid", NOW.plusSeconds(4)));
    }

    @Test
    void wireContractsAndSearchCriteriaRejectInvalidInput() {
        assertEquals(FacilityKind.SITE, FacilityKind.parse(" site "));
        assertEquals("room", FacilityKind.ROOM.wireValue());
        assertThrows(IllegalArgumentException.class, () -> FacilityKind.parse("unknown"));
        assertEquals(FacilityStatus.MAINTENANCE, FacilityStatus.parse("maintenance"));
        assertThrows(IllegalArgumentException.class, () -> FacilityStatus.parse("unknown"));
        assertEquals("PAR_01", new FacilityCode(" par_01 ").value());
        for (String code : List.of("A", "-BAD", "A".repeat(65))) assertThrows(IllegalArgumentException.class, () -> new FacilityCode(code));

        var criteria = new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, null, "fr", null, 200);
        assertEquals("FR", criteria.countryCode());
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.ROOM, null, null, "FR", null, 20));
        assertThrows(IllegalArgumentException.class, () -> new io.infranexum.dcim.facility.application.FacilitySearchCriteria(ORG, SUB, FacilityKind.SITE, null, null, null, null, 201));
    }

    private static FacilityNode site(String country, String timezone, String description) {
        return siteWithAddress("10 Rue de Rivoli", null, "75001", "Paris", country, timezone, description);
    }

    private static FacilityNode siteWithAddress(String line1, String line2, String postal, String city, String country, String timezone, String description) {
        return FacilityNode.draft(ID, FacilityKind.SITE, ORG, SUB, null, SUB, new FacilityCode("PAR01"), "Paris primary site",
                line1, line2, postal, city, country, timezone, new BigDecimal("48.8566000"), new BigDecimal("2.3522000"),
                null, null, null, null, null, null, null, description, ACTOR, "Initial registration", NOW);
    }

    private static FacilityNode draft(FacilityKind kind, DomainIdentifier parent, DomainIdentifier scope,
            String line1, String line2, String postal, String city, String country, String timezone,
            BigDecimal latitude, BigDecimal longitude, Integer floorCount, Integer levelNumber, BigDecimal area,
            BigDecimal height, BigDecimal capacity, String access, String zoneType) {
        return FacilityNode.draft(ID, kind, ORG, SUB, parent, scope, new FacilityCode("NODE1"), "Facility node",
                line1, line2, postal, city, country, timezone, latitude, longitude, floorCount, levelNumber, area, height,
                capacity, access, zoneType, "Facility description", ACTOR, "Initial registration", NOW);
    }

    private static DomainIdentifier id(String value) { return DomainIdentifier.parse(value); }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        FacilityConflictException failure = assertThrows(FacilityConflictException.class, executable);
        assertEquals(code, failure.code());
    }
}
