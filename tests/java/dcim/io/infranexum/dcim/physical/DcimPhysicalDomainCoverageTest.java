package io.infranexum.dcim.physical;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.physical.application.PhysicalCommandContext;
import io.infranexum.dcim.physical.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exhaustive non-regression coverage for DCIM physical value objects and lifecycle guards. */
final class DcimPhysicalDomainCoverageTest {
    private static final DomainIdentifier ID = id(1);
    private static final DomainIdentifier ORG = id(2);
    private static final DomainIdentifier SUB = id(3);
    private static final DomainIdentifier ROOM = id(4);
    private static final DomainIdentifier MODEL = id(5);
    private static final DomainIdentifier RSOT = id(6);
    private static final DomainIdentifier ITAM = id(7);
    private static final DomainIdentifier ACTOR = id(8);
    private static final Instant NOW = Instant.parse("2026-08-16T16:00:00Z");

    @Test
    void portTemplatesPortsEnumsAndContextCoverAllBounds() {
        PortTemplate template = new PortTemplate("eth", 2, PortKind.NETWORK, " COPPER ", " RJ45 ");
        assertEquals("eth1", template.portName(1));
        assertEquals("eth2", template.portName(2));
        assertEquals("copper", template.media());
        assertEquals("rj45", template.connector());
        assertEquals("network", PortKind.NETWORK.wireValue());
        for (PortKind kind : PortKind.values()) assertFalse(kind.wireValue().isBlank());
        assertEquals(PhysicalStatus.ACTIVE, PhysicalStatus.parse(" active "));
        for (PhysicalStatus status : PhysicalStatus.values()) assertFalse(status.wireValue().isBlank());

        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("", 1, PortKind.NETWORK, "copper", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("eth", 0, PortKind.NETWORK, "copper", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("eth", 513, PortKind.NETWORK, "copper", "rj45"));
        assertThrows(NullPointerException.class, () -> new PortTemplate("eth", 1, null, "copper", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("eth", 1, PortKind.NETWORK, "bad media", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> template.portName(0));
        assertThrows(IllegalArgumentException.class, () -> template.portName(3));

        PhysicalPort port = new PhysicalPort(ID, ORG, MODEL, " eth0 ", PortKind.NETWORK, " COPPER ", " RJ45 ", false);
        assertEquals("eth0", port.name());
        assertEquals("copper", port.media());
        assertFalse(port.connected());
        assertThrows(NullPointerException.class, () -> new PhysicalPort(null, ORG, MODEL, "eth0", PortKind.NETWORK, "copper", "rj45", false));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalPort(ID, ORG, MODEL, " ", PortKind.NETWORK, "copper", "rj45", false));
        assertThrows(NullPointerException.class, () -> new PhysicalPort(ID, ORG, MODEL, "eth0", null, "copper", "rj45", false));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalPort(ID, ORG, MODEL, "eth0", PortKind.NETWORK, "bad\u0001", "rj45", false));

        PhysicalCommandContext context = new PhysicalCommandContext(ACTOR, ID, " governed change ");
        assertEquals("governed change", context.reason());
        assertThrows(NullPointerException.class, () -> new PhysicalCommandContext(null, ID, "reason"));
        assertThrows(NullPointerException.class, () -> new PhysicalCommandContext(ACTOR, null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCommandContext(ACTOR, ID, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCommandContext(ACTOR, ID, "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCommandContext(ACTOR, ID, "reason\n"));
    }

    @Test
    void equipmentModelsValidateDimensionsPortsStatusesAndAccessors() {
        PortTemplate template = new PortTemplate("eth", 2, PortKind.NETWORK, "copper", "rj45");
        EquipmentModel model = EquipmentModel.draft(
                ID, ORG, ACTOR, " R740 ", " PowerEdge R740 ", " rack ", 2, 482, 800,
                new BigDecimal("24.500"), List.of(template), " server model ", ACTOR, "create model", NOW);
        assertAll(
                () -> assertEquals(ID, model.id()),
                () -> assertEquals(ORG, model.organizationId()),
                () -> assertEquals(ACTOR, model.manufacturerPartnerId()),
                () -> assertEquals("R740", model.code()),
                () -> assertEquals("PowerEdge R740", model.displayName()),
                () -> assertEquals("rack", model.formFactor()),
                () -> assertEquals(2, model.rackUnits()),
                () -> assertEquals(482, model.widthMm()),
                () -> assertEquals(800, model.depthMm()),
                () -> assertEquals(new BigDecimal("24.5"), model.weightKg()),
                () -> assertEquals(List.of(template), model.portTemplates()),
                () -> assertEquals(PhysicalStatus.DRAFT, model.status()),
                () -> assertEquals("server model", model.description()),
                () -> assertEquals(1L, model.version()),
                () -> assertEquals(NOW, model.createdAt()),
                () -> assertEquals(NOW, model.updatedAt()),
                () -> assertEquals(ACTOR, model.createdBy()),
                () -> assertEquals(ACTOR, model.updatedBy()),
                () -> assertEquals("create model", model.lastReason()));
        EquipmentModel active = model.changeStatus(PhysicalStatus.ACTIVE, ACTOR, "activate model", NOW.plusSeconds(1));
        assertEquals(2L, active.version());
        assertThrows(DcimPhysicalConflictException.class,
                () -> active.changeStatus(PhysicalStatus.MAINTENANCE, ACTOR, "invalid", NOW.plusSeconds(2)));
        EquipmentModel archived = active.changeStatus(PhysicalStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(2));
        assertThrows(DcimPhysicalConflictException.class,
                () -> archived.changeStatus(PhysicalStatus.ACTIVE, ACTOR, "invalid", NOW.plusSeconds(3)));

        assertThrows(IllegalArgumentException.class, () -> model(0, 482, 800, BigDecimal.ONE, List.of(template), null));
        assertThrows(IllegalArgumentException.class, () -> model(101, 482, 800, BigDecimal.ONE, List.of(template), null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 0, 800, BigDecimal.ONE, List.of(template), null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 5001, BigDecimal.ONE, List.of(template), null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 800, null, List.of(template), null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 800, BigDecimal.ZERO, List.of(template), null));
        assertThrows(NullPointerException.class, () -> model(1, 482, 800, BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 800, BigDecimal.ONE, List.of(), null));
        PortTemplate huge = new PortTemplate("p", 512, PortKind.OTHER, "other", "other");
        assertThrows(IllegalArgumentException.class,
                () -> model(1, 482, 800, BigDecimal.ONE, List.of(huge, huge, huge, huge, huge), null));
        assertNull(model(1, 482, 800, BigDecimal.ONE, List.of(template), " ").description());
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 800, BigDecimal.ONE, List.of(template), "x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> EquipmentModel.restore(
                ID, ORG, ACTOR, "R740", "PowerEdge", "rack", 1, 482, 800, BigDecimal.ONE,
                List.of(template), PhysicalStatus.ACTIVE, null, 0, NOW, NOW, ACTOR, ACTOR, "reason"));
    }

    @Test
    void rackEquipmentAndCableLifecycleBranchesAreFailClosed() {
        Rack rack = Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack one", 42, 600, 1200, ACTOR, "create rack", NOW);
        assertAll(
                () -> assertEquals(ROOM, rack.roomId()), () -> assertEquals("R01", rack.code()),
                () -> assertEquals(42, rack.heightU()), () -> assertEquals(600, rack.widthMm()),
                () -> assertEquals(1200, rack.depthMm()), () -> assertEquals(PhysicalStatus.DRAFT, rack.status()),
                () -> assertEquals(ACTOR, rack.createdBy()), () -> assertEquals("create rack", rack.lastReason()));
        Rack archivedRack = rack.changeStatus(PhysicalStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(1));
        assertThrows(DcimPhysicalConflictException.class,
                () -> archivedRack.changeStatus(PhysicalStatus.ACTIVE, ACTOR, "invalid", NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack one", 0, 600, 1200, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack one", 42, 0, 1200, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.restore(ID, ORG, SUB, ROOM, "R01", "Rack one", 42, 600, 1200, PhysicalStatus.ACTIVE, 0, NOW, NOW, ACTOR, ACTOR, "reason"));

        Equipment equipment = Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, ITAM, " SN-1 ", " AT-1 ", 10, " FRONT ", ACTOR, "install", NOW);
        assertAll(
                () -> assertEquals(ROOM, equipment.rackId()), () -> assertEquals(MODEL, equipment.modelId()),
                () -> assertEquals(RSOT, equipment.rsotObjectId()), () -> assertEquals(ITAM, equipment.itamAssetId()),
                () -> assertEquals("SN-1", equipment.serialNumber()), () -> assertEquals("AT-1", equipment.assetTag()),
                () -> assertEquals(10, equipment.startU()), () -> assertEquals("front", equipment.face()),
                () -> assertEquals(PhysicalStatus.ACTIVE, equipment.status()), () -> assertEquals(1L, equipment.version()));
        Equipment moved = equipment.move(ROOM, 12, "rear", ACTOR, "move", NOW.plusSeconds(1));
        assertEquals(12, moved.startU());
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, null, 0, "front", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, null, 101, "front", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, null, 1, "side", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.restore(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, null, 1, "front", PhysicalStatus.ACTIVE, 0, NOW, NOW, ACTOR, ACTOR, "reason"));
        Equipment retired = equipment.changeStatus(PhysicalStatus.DECOMMISSIONED, ACTOR, "retire", NOW.plusSeconds(1));
        assertThrows(DcimPhysicalConflictException.class, () -> retired.move(ROOM, 20, "front", ACTOR, "move", NOW.plusSeconds(2)));
        Equipment archived = equipment.changeStatus(PhysicalStatus.ARCHIVED, ACTOR, "archive", NOW.plusSeconds(1));
        assertThrows(DcimPhysicalConflictException.class, () -> archived.move(ROOM, 20, "front", ACTOR, "move", NOW.plusSeconds(2)));
        assertThrows(DcimPhysicalConflictException.class, () -> archived.changeStatus(PhysicalStatus.ACTIVE, ACTOR, "activate", NOW.plusSeconds(2)));

        DomainIdentifier portA = id(20), portB = id(21);
        CableConnection cable = CableConnection.active(ID, ORG, SUB, portA, portB, " C-1 ", " COPPER ", " RJ45 ", ACTOR, "connect", NOW);
        assertAll(
                () -> assertEquals(portA, cable.portAId()), () -> assertEquals(portB, cable.portBId()),
                () -> assertEquals("C-1", cable.label()), () -> assertEquals("copper", cable.media()),
                () -> assertEquals("rj45", cable.connector()), () -> assertEquals(PhysicalStatus.ACTIVE, cable.status()),
                () -> assertEquals(ACTOR, cable.updatedBy()));
        CableConnection disconnected = cable.disconnect(ACTOR, "disconnect", NOW.plusSeconds(1));
        assertEquals(PhysicalStatus.DECOMMISSIONED, disconnected.status());
        assertThrows(DcimPhysicalConflictException.class, () -> disconnected.disconnect(ACTOR, "again", NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, portA, portA, "C", "copper", "rj45", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.restore(ID, ORG, SUB, portA, portB, "C", "copper", "rj45", PhysicalStatus.ACTIVE, 0, NOW, NOW, ACTOR, ACTOR, "reason"));

        DcimPhysicalConflictException conflict = new DcimPhysicalConflictException("DCIM_TEST", "message");
        assertEquals("DCIM_TEST", conflict.code());
        assertEquals("message", conflict.getMessage());
        assertEquals("rack not found", new DcimPhysicalNotFoundException("rack").getMessage());
    }


    @Test
    void scalarValidationSaturatesIndependentMinimumMaximumAndControlCharacterBranches() {
        PortTemplate one = new PortTemplate("p", 1, PortKind.NETWORK, "copper", "rj45");
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("p", 1, PortKind.NETWORK, "_bad", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("p", 1, PortKind.NETWORK, "copper", "bad connector"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("x".repeat(25), 1, PortKind.NETWORK, "copper", "rj45"));
        assertThrows(IllegalArgumentException.class, () -> new PortTemplate("p\n", 1, PortKind.NETWORK, "copper", "rj45"));

        assertThrows(IllegalArgumentException.class, () -> new PhysicalPort(ID, ORG, MODEL, "x".repeat(65), PortKind.NETWORK, "copper", "rj45", false));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalPort(ID, ORG, MODEL, "eth0", PortKind.NETWORK, "x".repeat(33), "rj45", false));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalPort(ID, ORG, MODEL, "eth0", PortKind.NETWORK, "copper", "x".repeat(33), false));

        assertThrows(IllegalArgumentException.class, () -> model(1, 5001, 800, BigDecimal.ONE, List.of(one), null));
        assertThrows(IllegalArgumentException.class, () -> model(1, 482, 0, BigDecimal.ONE, List.of(one), null));
        assertThrows(IllegalArgumentException.class, () -> EquipmentModel.draft(ID, ORG, ACTOR, "_bad", "Model one", "rack", 1, 482, 800, BigDecimal.ONE, List.of(one), null, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> EquipmentModel.draft(ID, ORG, ACTOR, "X", "Model one", "rack", 1, 482, 800, BigDecimal.ONE, List.of(one), null, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> EquipmentModel.draft(ID, ORG, ACTOR, "MOD", "x".repeat(129), "rack", 1, 482, 800, BigDecimal.ONE, List.of(one), null, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> EquipmentModel.draft(ID, ORG, ACTOR, "MOD", "Model", "r\n", 1, 482, 800, BigDecimal.ONE, List.of(one), null, ACTOR, "reason", NOW));

        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack", 101, 600, 1000, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack", 42, 5001, 1000, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack", 42, 600, 0, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "Rack", 42, 600, 5001, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "X", "Rack", 42, 600, 1000, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R01", "x".repeat(129), 42, 600, 1000, ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Rack.draft(ID, ORG, SUB, ROOM, "R\n", "Rack", 42, 600, 1000, ACTOR, "reason", NOW));

        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, "x".repeat(129), null, 1, "front", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, "x".repeat(129), 1, "front", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, "SN\n", null, 1, "front", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, null, null, 1, "x", ACTOR, "reason", NOW));
        assertNull(Equipment.installed(ID, ORG, SUB, ROOM, MODEL, RSOT, null, " ", " ", 1, "front", ACTOR, "reason", NOW).serialNumber());

        DomainIdentifier a=id(30), b=id(31);
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, a, b, "x".repeat(129), "copper", "rj45", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, a, b, "C", "x".repeat(33), "rj45", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, a, b, "C", "copper", "x".repeat(33), ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, a, b, "C\n", "copper", "rj45", ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> CableConnection.active(ID, ORG, SUB, a, b, " ", "copper", "rj45", ACTOR, "reason", NOW));
    }

    private static EquipmentModel model(int rackUnits, int width, int depth, BigDecimal weight, List<PortTemplate> templates, String description) {
        return EquipmentModel.draft(ID, ORG, ACTOR, "MODEL1", "Model one", "rack", rackUnits, width, depth,
                weight, templates, description, ACTOR, "create", NOW);
    }

    private static DomainIdentifier id(int n) {
        return DomainIdentifier.parse("01900000-0000-7000-8000-%012d".formatted(n));
    }
}
