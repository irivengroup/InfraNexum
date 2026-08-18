package io.infranexum.ddi.ipam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.ddi.ipam.application.IpamCommandContext;
import io.infranexum.ddi.ipam.domain.AddressStatus;
import io.infranexum.ddi.ipam.domain.IpCidr;
import io.infranexum.ddi.ipam.domain.IpamAddress;
import io.infranexum.ddi.ipam.domain.IpamConflictException;
import io.infranexum.ddi.ipam.domain.IpamNetwork;
import io.infranexum.ddi.ipam.domain.IpamNotFoundException;
import io.infranexum.ddi.ipam.domain.IpamPool;
import io.infranexum.ddi.ipam.domain.IpamStatus;
import io.infranexum.ddi.ipam.domain.IpamVlan;
import io.infranexum.ddi.ipam.domain.IpamVrf;
import io.infranexum.ddi.ipam.domain.NetworkKind;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Exhaustive boundary coverage for IPAM value objects independent from persistence. */
final class IpamDomainCoverageTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void cidrCanonicalizationContainmentOverlapAndFamiliesAreStrict() {
        IpCidr subnet = new IpCidr(" 10.20.30.42/24 ");
        IpCidr child = new IpCidr("10.20.30.128/25");
        IpCidr other = new IpCidr("10.20.31.0/24");
        IpCidr ipv6 = new IpCidr("2001:db8::1/64");

        assertEquals("10.20.30.0/24", subnet.value());
        assertEquals(24, subnet.prefixLength());
        assertEquals(32, subnet.addressBits());
        assertEquals("10.20.30.0", subnet.firstAddress());
        assertEquals("10.20.30.255", subnet.lastAddress());
        assertEquals(256, subnet.size().intValueExact());
        assertTrue(subnet.contains(child));
        assertTrue(subnet.overlaps(child));
        assertFalse(subnet.overlaps(other));
        assertFalse(subnet.contains(ipv6));
        assertFalse(subnet.overlaps(ipv6));
        assertFalse(ipv6.contains(subnet));
        assertFalse(ipv6.overlaps(subnet));
        assertFalse(subnet.equals("10.20.30.0/24"));
        assertTrue(subnet.containsAddress("10.20.30.1"));
        assertFalse(subnet.containsAddress("10.20.31.1"));
        assertEquals(subnet.firstSortKey(), IpCidr.sortKey("10.20.30.0"));
        assertEquals(subnet.lastSortKey(), IpCidr.sortKey("10.20.30.255"));
        assertEquals("10.20.30.1", IpCidr.canonicalAddress(" 10.20.30.1 "));
        assertEquals(subnet, new IpCidr("10.20.30.0/24"));
        assertEquals(subnet.hashCode(), new IpCidr("10.20.30.0/24").hashCode());
        assertNotEquals(subnet, other);
        assertEquals(subnet.value(), subnet.toString());

        assertThrows(NullPointerException.class, () -> new IpCidr(null));
        assertThrows(IllegalArgumentException.class, () -> new IpCidr("10.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> new IpCidr("10.0.0.1/not-a-prefix"));
        assertThrows(IllegalArgumentException.class, () -> new IpCidr("10.0.0.1/33"));
        assertThrows(IllegalArgumentException.class, () -> new IpCidr("300.300.300.300/24"));
        assertThrows(NullPointerException.class, () -> IpCidr.canonicalAddress(null));
        assertThrows(IllegalArgumentException.class, () -> IpCidr.canonicalAddress("300.300.300.300"));
        assertThrows(NullPointerException.class, () -> IpCidr.sortKey(null));
        assertThrows(IllegalArgumentException.class, () -> IpCidr.sortKey("300.300.300.300"));
        assertThrows(IllegalArgumentException.class, () -> subnet.containsAddress("2001:db8::1"));
    }

    @Test
    void enumsRoundTripAndRejectUnknownWireValues() {
        for (AddressStatus value : AddressStatus.values()) {
            assertEquals(value, AddressStatus.parse(value.wireValue()));
        }
        for (IpamStatus value : IpamStatus.values()) {
            assertEquals(value, IpamStatus.parse(value.wireValue()));
        }
        for (NetworkKind value : NetworkKind.values()) {
            assertEquals(value, NetworkKind.parse(value.wireValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> AddressStatus.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> IpamStatus.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> NetworkKind.parse("unknown"));
    }

    @Test
    void vrfLifecycleAndValidationCoverAllTerminalBranches() {
        DomainIdentifier id = id();
        DomainIdentifier org = id();
        IpamVrf draft = IpamVrf.draft(id, org, " core ", " Core routing ", " 65000:1 ", NOW);
        assertEquals("core", draft.code());
        assertEquals("Core routing", draft.displayName());
        assertEquals("65000:1", draft.routeDistinguisher());
        assertEquals(IpamStatus.DRAFT, draft.status());
        assertEquals(1, draft.version());

        IpamVrf active = draft.status(IpamStatus.ACTIVE, NOW.plusSeconds(1));
        assertEquals(2, active.version());
        assertThrows(IpamConflictException.class, () -> active.status(IpamStatus.DRAFT, NOW));
        IpamVrf retired = active.status(IpamStatus.RETIRED, NOW.plusSeconds(2));
        assertThrows(IpamConflictException.class, () -> retired.status(IpamStatus.ACTIVE, NOW));

        assertThrows(NullPointerException.class, () -> new IpamVrf(null, org, "c", "n", null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVrf(id, null, "c", "n", null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, " ", "n", null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, "x".repeat(65), "n", null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, "c", " ", null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, "c", "x".repeat(161), null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertNull(new IpamVrf(id, org, "c", "n", " ", IpamStatus.DRAFT, 1, NOW, NOW).routeDistinguisher());
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, "c", "n", "x".repeat(129), IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVrf(id, org, "c", "n", null, null, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVrf(id, org, "c", "n", null, IpamStatus.DRAFT, 0, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVrf(id, org, "c", "n", null, IpamStatus.DRAFT, 1, null, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVrf(id, org, "c", "n", null, IpamStatus.DRAFT, 1, NOW, null));
    }

    @Test
    void vlanNetworkPoolAndAddressModelsRejectInvalidBoundaries() {
        DomainIdentifier org = id();
        DomainIdentifier vrf = id();
        DomainIdentifier site = id();
        DomainIdentifier vlanId = id();
        DomainIdentifier networkId = id();
        DomainIdentifier poolId = id();

        IpamVlan vlan = IpamVlan.draft(vlanId, org, site, 100, 5000L, " apps ", NOW);
        assertEquals("apps", vlan.name());
        assertEquals(100, vlan.vlanId());
        assertEquals(5000L, vlan.vni());
        assertEquals(2, vlan.status(IpamStatus.ACTIVE, NOW).version());
        IpamVlan retiredVlan = vlan.status(IpamStatus.RETIRED, NOW);
        assertThrows(IpamConflictException.class, () -> retiredVlan.status(IpamStatus.ACTIVE, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, null, null, "name", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, 0, null, "name", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, 4095, null, "name", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, null, 0L, "name", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, null, 16_777_216L, "name", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, 1, null, " ", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, 1, null, "x".repeat(161), IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamVlan(vlanId, org, site, 1, null, "name", IpamStatus.DRAFT, 0, NOW, NOW));

        IpamNetwork network = IpamNetwork.draft(id(), org, id(), site, vrf, vlanId, null, NetworkKind.SUBNET,
                new IpCidr("10.1.0.0/24"), " apps ", " trusted ", NOW);
        assertEquals(256, network.addressCount().intValueExact());
        assertEquals("apps", network.usage());
        assertEquals("trusted", network.trustLevel());
        IpamNetwork metadata = network.metadata(null, " ", " ", NOW.plusSeconds(1));
        assertNull(metadata.usage());
        assertNull(metadata.trustLevel());
        IpamNetwork retiredNetwork = network.status(IpamStatus.RETIRED, NOW.plusSeconds(1));
        assertThrows(IpamConflictException.class, () -> retiredNetwork.status(IpamStatus.ACTIVE, NOW));
        assertThrows(IpamConflictException.class, () -> retiredNetwork.metadata(null, null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamNetwork(id(), org, null, null, vrf, null, null, NetworkKind.SUBNET, new IpCidr("10.0.0.0/24"), "x".repeat(161), null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamNetwork(id(), org, null, null, vrf, null, null, NetworkKind.SUBNET, new IpCidr("10.0.0.0/24"), null, "x".repeat(65), IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamNetwork(id(), org, null, null, vrf, null, null, NetworkKind.SUBNET, new IpCidr("10.0.0.0/24"), null, null, IpamStatus.DRAFT, 0, NOW, NOW));

        IpamPool pool = IpamPool.active(poolId, org, networkId, "10.1.0.10", "10.1.0.20", " dynamic ", NOW);
        assertEquals("dynamic", pool.name());
        assertEquals("10.1.0.10", pool.allocationCursor());
        assertEquals("10.1.0.11", pool.advance("10.1.0.11", NOW).allocationCursor());
        assertThrows(IllegalArgumentException.class, () -> new IpamPool(poolId, org, networkId, "10.0.0.1", "10.0.0.2", "10.0.0.1", " ", IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamPool(poolId, org, networkId, "10.0.0.1", "10.0.0.2", "10.0.0.1", "x".repeat(161), IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamPool(poolId, org, networkId, "10.0.0.1", "10.0.0.2", "10.0.0.1", "n", IpamStatus.ACTIVE, 0, NOW, NOW));

        IpamAddress allocated = IpamAddress.assigned(id(), org, vrf, networkId, poolId, "10.1.0.10", false,
                " host.example ", null, null, " primary ", NOW);
        assertEquals(AddressStatus.ALLOCATED, allocated.status());
        assertEquals("host.example", allocated.hostname());
        assertEquals("primary", allocated.purpose());
        IpamAddress released = allocated.release(NOW.plusSeconds(1));
        assertEquals(AddressStatus.RELEASED, released.status());
        assertSame(released, released.release(NOW.plusSeconds(2)));
        assertEquals(AddressStatus.RESERVED, IpamAddress.assigned(id(), org, vrf, networkId, null, "10.1.0.11", true, null, null, null, null, NOW).status());
        assertNull(new IpamAddress(id(), org, vrf, networkId, null, "10.1.0.12", AddressStatus.ALLOCATED, " ", null, null, " ", 1, NOW, NOW).hostname());
        assertThrows(IllegalArgumentException.class, () -> new IpamAddress(id(), org, vrf, networkId, null, "10.1.0.12", AddressStatus.ALLOCATED, "x".repeat(254), null, null, null, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamAddress(id(), org, vrf, networkId, null, "10.1.0.12", AddressStatus.ALLOCATED, null, null, null, "x".repeat(513), 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamAddress(id(), org, vrf, networkId, null, "10.1.0.12", AddressStatus.ALLOCATED, null, null, null, null, 0, NOW, NOW));
    }

    @Test
    void commandContextAndExceptionsExposeStableFailClosedContracts() {
        DomainIdentifier actor = id();
        DomainIdentifier correlation = id();
        IpamCommandContext context = new IpamCommandContext(actor, correlation, " allocate address ", " ddi-ipam-0001 ");
        assertEquals("allocate address", context.reason());
        assertEquals("ddi-ipam-0001", context.idempotencyKey());
        assertThrows(NullPointerException.class, () -> new IpamCommandContext(null, correlation, "reason", "ddi-ipam-0001"));
        assertThrows(NullPointerException.class, () -> new IpamCommandContext(actor, null, "reason", "ddi-ipam-0001"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "x", "ddi-ipam-0001"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "x".repeat(1025), "ddi-ipam-0001"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "reason", "short"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "reason", "bad key value"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "reason\n", "ddi-ipam-0001"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "reason", "ddi-ipam-0001\n"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(actor, correlation, "reason", "x".repeat(201)));

        IpamConflictException conflict = new IpamConflictException("DDI_CONFLICT", "conflict");
        assertEquals("DDI_CONFLICT", conflict.code());
        assertEquals("conflict", conflict.getMessage());
        assertEquals("network not found", new IpamNotFoundException("network").getMessage());
    }

    @Test
    void coverageClosureExercisesIdempotencyRecordAndCidrCrossFamilyUtilities() {
        DomainIdentifier result = id();
        var record = new io.infranexum.ddi.ipam.ports.IpamIdempotencyRepository.Record(
                "ddi-coverage-0001", "a".repeat(64), "network.create", result, NOW);
        assertEquals("ddi-coverage-0001", record.key());
        assertEquals(result, record.resultId());
        assertEquals(NOW, record.createdAt());

        IpCidr ipv4 = new IpCidr("192.0.2.1/24");
        IpCidr ipv6 = new IpCidr("2001:db8::1/64");
        assertFalse(ipv4.overlaps(ipv6));
        assertFalse(ipv4.contains(ipv6));
        assertThrows(IllegalArgumentException.class, () -> ipv4.containsAddress("2001:db8::1"));
        assertThrows(IllegalArgumentException.class, () -> IpCidr.canonicalAddress("not-an-ip.invalid"));
        assertThrows(IllegalArgumentException.class, () -> IpCidr.sortKey("not-an-ip.invalid"));
        assertEquals(ipv4, new IpCidr("192.0.2.200/24"));
        assertEquals(ipv4.hashCode(), new IpCidr("192.0.2.0/24").hashCode());
        assertEquals(ipv4.value(), ipv4.toString());
        assertTrue(ipv6.firstSortKey() != null);
        assertTrue(ipv6.lastSortKey() != null);
    }


    @Test
    void generatedRecordAndAccessorSurfaceIsFullyExercised() {
        DomainIdentifier org = id();
        DomainIdentifier vrfId = id();
        DomainIdentifier site = id();
        DomainIdentifier vlanId = id();
        DomainIdentifier networkId = id();
        DomainIdentifier poolId = id();
        DomainIdentifier actor = id();
        DomainIdentifier correlation = id();
        DomainIdentifier rsot = id();
        DomainIdentifier equipment = id();

        IpCidr cidr = new IpCidr("198.51.100.42/24");
        assertEquals(cidr.first(), new java.math.BigInteger("3325256704"));
        assertEquals(cidr.last(), new java.math.BigInteger("3325256959"));

        var vrfCommand = new io.infranexum.ddi.ipam.application.CreateVrfCommand(org, "EDGE", "Edge", "65000:8");
        assertEquals(org, vrfCommand.organizationId());
        assertEquals("EDGE", vrfCommand.code());
        assertEquals("Edge", vrfCommand.displayName());
        assertEquals("65000:8", vrfCommand.routeDistinguisher());
        assertEquals(vrfCommand, new io.infranexum.ddi.ipam.application.CreateVrfCommand(org, "EDGE", "Edge", "65000:8"));
        assertEquals(vrfCommand.hashCode(), new io.infranexum.ddi.ipam.application.CreateVrfCommand(org, "EDGE", "Edge", "65000:8").hashCode());
        assertTrue(vrfCommand.toString().contains("EDGE"));

        var vlanCommand = new io.infranexum.ddi.ipam.application.CreateVlanCommand(org, site, 8, 8008L, "edge");
        assertEquals(org, vlanCommand.organizationId()); assertEquals(site, vlanCommand.siteId());
        assertEquals(8, vlanCommand.vlanId()); assertEquals(8008L, vlanCommand.vni()); assertEquals("edge", vlanCommand.name());
        assertEquals(vlanCommand, new io.infranexum.ddi.ipam.application.CreateVlanCommand(org, site, 8, 8008L, "edge"));
        assertTrue(vlanCommand.hashCode() != 0); assertTrue(vlanCommand.toString().contains("edge"));

        var networkCommand = new io.infranexum.ddi.ipam.application.CreateNetworkCommand(org, id(), site, vrfId, vlanId, null,
                NetworkKind.SUBNET, "198.51.100.0/24", "edge", "trusted");
        assertEquals(org, networkCommand.organizationId()); assertEquals(site, networkCommand.siteId()); assertEquals(vrfId, networkCommand.vrfId());
        assertEquals(vlanId, networkCommand.vlanId()); assertNull(networkCommand.parentNetworkId()); assertEquals(NetworkKind.SUBNET, networkCommand.kind());
        assertEquals("198.51.100.0/24", networkCommand.cidr()); assertEquals("edge", networkCommand.usage()); assertEquals("trusted", networkCommand.trustLevel());
        assertTrue(networkCommand.toString().contains("SUBNET")); assertTrue(networkCommand.hashCode() != 0);

        var poolCommand = new io.infranexum.ddi.ipam.application.CreatePoolCommand(org, networkId, "198.51.100.10", "198.51.100.20", "edge-pool");
        assertEquals(org, poolCommand.organizationId()); assertEquals(networkId, poolCommand.networkId()); assertEquals("198.51.100.10", poolCommand.startAddress());
        assertEquals("198.51.100.20", poolCommand.endAddress()); assertEquals("edge-pool", poolCommand.name()); assertTrue(poolCommand.toString().contains("edge-pool"));

        var allocate = new io.infranexum.ddi.ipam.application.AllocateAddressCommand(org, vrfId, networkId, poolId, "198.51.100.10", true,
                "host.example", rsot, equipment, "reservation");
        assertEquals(org, allocate.organizationId()); assertEquals(vrfId, allocate.vrfId()); assertEquals(networkId, allocate.networkId()); assertEquals(poolId, allocate.poolId());
        assertEquals("198.51.100.10", allocate.requestedAddress()); assertTrue(allocate.reservation()); assertEquals("host.example", allocate.hostname());
        assertEquals(rsot, allocate.rsotObjectId()); assertEquals(equipment, allocate.dcimEquipmentId()); assertEquals("reservation", allocate.purpose());
        assertTrue(allocate.toString().contains("reservation")); assertTrue(allocate.hashCode() != 0);

        var update = new io.infranexum.ddi.ipam.application.UpdateNetworkCommand(vlanId, "new-usage", "restricted");
        assertEquals(vlanId, update.vlanId()); assertEquals("new-usage", update.usage()); assertEquals("restricted", update.trustLevel());
        assertEquals(update, new io.infranexum.ddi.ipam.application.UpdateNetworkCommand(vlanId, "new-usage", "restricted"));
        assertTrue(update.hashCode() != 0); assertTrue(update.toString().contains("new-usage"));

        IpamVrf vrf = IpamVrf.draft(vrfId, org, "EDGE", "Edge", null, NOW);
        assertEquals(vrfId, vrf.id()); assertEquals(org, vrf.organizationId()); assertEquals(NOW, vrf.createdAt()); assertEquals(NOW, vrf.updatedAt());
        assertEquals(vrf, IpamVrf.draft(vrfId, org, "EDGE", "Edge", null, NOW)); assertTrue(vrf.hashCode() != 0); assertTrue(vrf.toString().contains("EDGE"));

        IpamVlan vlan = IpamVlan.draft(vlanId, org, site, 8, 8008L, "edge", NOW);
        assertEquals(vlanId, vlan.id()); assertEquals(org, vlan.organizationId()); assertEquals(site, vlan.siteId()); assertEquals(NOW, vlan.createdAt()); assertEquals(NOW, vlan.updatedAt());
        assertEquals(vlan, IpamVlan.draft(vlanId, org, site, 8, 8008L, "edge", NOW)); assertTrue(vlan.hashCode() != 0); assertTrue(vlan.toString().contains("edge"));

        IpamNetwork network = IpamNetwork.draft(networkId, org, null, site, vrfId, vlanId, null, NetworkKind.SUBNET, cidr, "edge", "trusted", NOW);
        assertEquals(networkId, network.id()); assertEquals(org, network.organizationId()); assertNull(network.subdivisionId()); assertEquals(site, network.siteId());
        assertEquals(vrfId, network.vrfId()); assertEquals(vlanId, network.vlanId()); assertNull(network.parentNetworkId()); assertEquals(NetworkKind.SUBNET, network.kind());
        assertEquals(cidr, network.cidr()); assertEquals(IpamStatus.DRAFT, network.status()); assertEquals(NOW, network.createdAt()); assertEquals(NOW, network.updatedAt());
        assertEquals(network, IpamNetwork.draft(networkId, org, null, site, vrfId, vlanId, null, NetworkKind.SUBNET, cidr, "edge", "trusted", NOW));
        assertTrue(network.hashCode() != 0); assertTrue(network.toString().contains("198.51.100.0/24"));

        IpamPool pool = IpamPool.active(poolId, org, networkId, "198.51.100.10", "198.51.100.20", "edge-pool", NOW);
        assertEquals(poolId, pool.id()); assertEquals(org, pool.organizationId()); assertEquals(networkId, pool.networkId()); assertEquals(IpamStatus.ACTIVE, pool.status());
        assertEquals(NOW, pool.createdAt()); assertEquals(NOW, pool.updatedAt()); assertEquals(pool, IpamPool.active(poolId, org, networkId, "198.51.100.10", "198.51.100.20", "edge-pool", NOW));
        assertTrue(pool.hashCode() != 0); assertTrue(pool.toString().contains("edge-pool"));

        IpamAddress address = IpamAddress.assigned(id(), org, vrfId, networkId, poolId, "198.51.100.10", true, "host.example", rsot, equipment, "reservation", NOW);
        assertEquals(org, address.organizationId()); assertEquals(vrfId, address.vrfId()); assertEquals(networkId, address.networkId()); assertEquals(poolId, address.poolId());
        assertEquals(rsot, address.rsotObjectId()); assertEquals(equipment, address.dcimEquipmentId()); assertEquals(NOW, address.createdAt()); assertEquals(NOW, address.updatedAt());
        assertTrue(address.hashCode() != 0); assertTrue(address.toString().contains("198.51.100.10"));

        IpamCommandContext context = new IpamCommandContext(actor, correlation, "coverage", "ddi-accessors-0001");
        assertEquals(actor, context.actorId()); assertEquals(correlation, context.correlationId()); assertTrue(context.toString().contains("coverage")); assertTrue(context.hashCode() != 0);

        var ledger = new io.infranexum.ddi.ipam.ports.IpamIdempotencyRepository.Record("ddi-accessors-0002", "b".repeat(64), "address.allocate", address.id(), NOW);
        assertEquals("b".repeat(64), ledger.payloadSha256()); assertEquals("address.allocate", ledger.operation());
        assertEquals(ledger, new io.infranexum.ddi.ipam.ports.IpamIdempotencyRepository.Record("ddi-accessors-0002", "b".repeat(64), "address.allocate", address.id(), NOW));
        assertTrue(ledger.hashCode() != 0); assertTrue(ledger.toString().contains("address.allocate"));
    }

    private static DomainIdentifier id() {
        return new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 2, 3, 4})).next();
    }
}
