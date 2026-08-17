package io.infranexum.ddi.ipam;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.ddi.ipam.application.IpamCommandContext;
import io.infranexum.ddi.ipam.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Saturates scalar and lifecycle branches that protect the IPAM public model. */
final class IpamBoundarySaturationTest {
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier A = id(1);
    private static final DomainIdentifier B = id(2);
    private static final DomainIdentifier C = id(3);
    private static final DomainIdentifier D = id(4);

    @Test
    void vlanAcceptsBothBoundaryRangesAndRejectsEachIndependentOperand() {
        assertEquals(1, new IpamVlan(A, B, null, 1, null, "n", IpamStatus.DRAFT, 1, NOW, NOW).vlanId());
        assertEquals(4094, new IpamVlan(A, B, null, 4094, null, "n", IpamStatus.DRAFT, 1, NOW, NOW).vlanId());
        assertEquals(1L, new IpamVlan(A, B, null, null, 1L, "n", IpamStatus.DRAFT, 1, NOW, NOW).vni());
        assertEquals(16_777_215L, new IpamVlan(A, B, null, null, 16_777_215L, "n", IpamStatus.DRAFT, 1, NOW, NOW).vni());
        assertThrows(NullPointerException.class, () -> new IpamVlan(null, B, null, 1, null, "n", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVlan(A, null, null, 1, null, "n", IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVlan(A, B, null, 1, null, null, IpamStatus.DRAFT, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamVlan(A, B, null, 1, null, "n", null, 1, NOW, NOW));
    }

    @Test
    void optionalIpamStringsCoverNullBlankExactMaximumAndOverflow() {
        String rd128 = "r".repeat(128);
        String usage160 = "u".repeat(160);
        String trust64 = "t".repeat(64);
        String host253 = "h".repeat(253);
        String purpose512 = "p".repeat(512);
        IpamVrf vrf = new IpamVrf(A, B, "c", "n", rd128, IpamStatus.DRAFT, 1, NOW, NOW);
        assertEquals(rd128, vrf.routeDistinguisher());
        assertNull(new IpamVrf(A, B, "c", "n", null, IpamStatus.DRAFT, 1, NOW, NOW).routeDistinguisher());
        IpamNetwork network = new IpamNetwork(A, B, null, null, C, null, null, NetworkKind.SUBNET,
                new IpCidr("192.0.2.0/24"), usage160, trust64, IpamStatus.ACTIVE, 1, NOW, NOW);
        assertEquals(usage160, network.usage());
        assertEquals(trust64, network.trustLevel());
        IpamAddress address = new IpamAddress(A, B, C, D, null, "192.0.2.1", AddressStatus.ALLOCATED,
                host253, null, null, purpose512, 1, NOW, NOW);
        assertEquals(host253, address.hostname());
        assertEquals(purpose512, address.purpose());
        assertThrows(IllegalArgumentException.class, () -> new IpamAddress(A, B, C, D, null, "192.0.2.1", AddressStatus.ALLOCATED,
                "h".repeat(254), null, null, null, 1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new IpamAddress(A, B, C, D, null, "192.0.2.1", AddressStatus.ALLOCATED,
                null, null, null, "p".repeat(513), 1, NOW, NOW));
    }

    @Test
    void constructorsRejectEveryMandatoryNullAndPositiveVersionBoundary() {
        IpCidr cidr = new IpCidr("192.0.2.0/24");
        assertThrows(IllegalArgumentException.class, () -> new IpCidr("192.0.2.1/-1"));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(null, B, null, null, C, null, null, NetworkKind.SUBNET, cidr, null, null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(A, null, null, null, C, null, null, NetworkKind.SUBNET, cidr, null, null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(A, B, null, null, null, null, null, NetworkKind.SUBNET, cidr, null, null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(A, B, null, null, C, null, null, null, cidr, null, null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(A, B, null, null, C, null, null, NetworkKind.SUBNET, null, null, null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamNetwork(A, B, null, null, C, null, null, NetworkKind.SUBNET, cidr, null, null, null, 1, NOW, NOW));

        assertThrows(NullPointerException.class, () -> new IpamPool(null, B, C, "192.0.2.1", "192.0.2.2", "192.0.2.1", "n", IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamPool(A, null, C, "192.0.2.1", "192.0.2.2", "192.0.2.1", "n", IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamPool(A, B, null, "192.0.2.1", "192.0.2.2", "192.0.2.1", "n", IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamPool(A, B, C, "192.0.2.1", "192.0.2.2", "192.0.2.1", null, IpamStatus.ACTIVE, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamPool(A, B, C, "192.0.2.1", "192.0.2.2", "192.0.2.1", "n", null, 1, NOW, NOW));

        assertThrows(NullPointerException.class, () -> new IpamAddress(null, B, C, D, null, "192.0.2.1", AddressStatus.ALLOCATED, null, null, null, null, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamAddress(A, null, C, D, null, "192.0.2.1", AddressStatus.ALLOCATED, null, null, null, null, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamAddress(A, B, null, D, null, "192.0.2.1", AddressStatus.ALLOCATED, null, null, null, null, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamAddress(A, B, C, null, null, "192.0.2.1", AddressStatus.ALLOCATED, null, null, null, null, 1, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new IpamAddress(A, B, C, D, null, "192.0.2.1", null, null, null, null, null, 1, NOW, NOW));
    }

    @Test
    void commandContextExercisesEveryLengthOperandAtItsBoundary() {
        String reason1024 = "r".repeat(1024);
        String key200 = "k".repeat(200);
        assertEquals(reason1024, new IpamCommandContext(A, B, reason1024, "12345678").reason());
        assertEquals(key200, new IpamCommandContext(A, B, "ok", key200).idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(A, B, " ", "12345678"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(A, B, "r".repeat(1025), "12345678"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(A, B, "ok", "1234567"));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(A, B, "ok", "k".repeat(201)));
        assertThrows(IllegalArgumentException.class, () -> new IpamCommandContext(A, B, "ok", "abcdefgh/"));
    }

    @Test
    void releaseAndLifecycleHappyAndTerminalBranchesAreBothObservable() {
        IpamAddress active = IpamAddress.assigned(A, B, C, D, null, "192.0.2.10", false, null, null, null, null, NOW);
        IpamAddress released = active.release(NOW.plusSeconds(1));
        assertNotSame(active, released);
        assertSame(released, released.release(NOW.plusSeconds(2)));

        IpamNetwork network = IpamNetwork.draft(A, B, null, null, C, null, null, NetworkKind.SUBNET, new IpCidr("192.0.2.0/24"), null, null, NOW);
        assertEquals(IpamStatus.ACTIVE, network.status(IpamStatus.ACTIVE, NOW.plusSeconds(1)).status());
        IpamVlan vlan = IpamVlan.draft(A, B, null, 1, null, "v", NOW);
        assertEquals(IpamStatus.ACTIVE, vlan.status(IpamStatus.ACTIVE, NOW.plusSeconds(1)).status());
    }

    private static DomainIdentifier id(long n) {
        return new DomainIdentifier(new UUID(0x0198000000007000L + n, 0x8000000000000000L + n));
    }
}
