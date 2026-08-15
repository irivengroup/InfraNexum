package io.infranexum.ddi.ipam.ports;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.ddi.ipam.domain.*;
import java.util.*;

/** Durable IPAM persistence port. All allocation checks execute inside the current transaction. */
public interface IpamRepository {
 long countVrfs(); long countVlans(); long countNetworks(); long countAddresses();
 void lockRoutingEnvironment(DomainIdentifier organizationId,DomainIdentifier vrfId); void lockPool(DomainIdentifier poolId);
 boolean vrfCodeExists(DomainIdentifier organizationId,String code); boolean hasActiveNetworks(DomainIdentifier vrfId); boolean hasActiveNetworksForVlan(DomainIdentifier vlanId); boolean vlanExists(DomainIdentifier organizationId,Integer vlanId,Long vni); boolean networkOverlaps(DomainIdentifier organizationId,DomainIdentifier vrfId,IpCidr cidr,DomainIdentifier excludingId); boolean addressInUse(DomainIdentifier vrfId,String address); boolean poolOverlaps(DomainIdentifier networkId,String start,String end);
 Optional<IpamVrf> vrf(DomainIdentifier id); Optional<IpamVlan> vlan(DomainIdentifier id); Optional<IpamNetwork> network(DomainIdentifier id); Optional<IpamPool> pool(DomainIdentifier id); Optional<IpamAddress> address(DomainIdentifier id);
 List<IpamVrf> vrfs(DomainIdentifier organizationId,int limit); List<IpamVlan> vlans(DomainIdentifier organizationId,int limit); List<IpamNetwork> networks(DomainIdentifier organizationId,DomainIdentifier vrfId,int limit); List<IpamPool> pools(DomainIdentifier networkId,int limit); List<IpamAddress> addresses(DomainIdentifier organizationId,DomainIdentifier vrfId,DomainIdentifier networkId,int limit);
 void insertVrf(IpamVrf value); void updateVrf(IpamVrf value,long expectedVersion); void insertVlan(IpamVlan value); void updateVlan(IpamVlan value,long expectedVersion); void insertNetwork(IpamNetwork value); void updateNetwork(IpamNetwork value,long expectedVersion); void insertPool(IpamPool value); void updatePool(IpamPool value,long expectedVersion); void insertAddress(IpamAddress value); void updateAddress(IpamAddress value,long expectedVersion);
}
