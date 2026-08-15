package io.infranexum.ddi.ipam.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Organization-owned VLAN/VXLAN logical segment independent from IP prefixes. */
public record IpamVlan(DomainIdentifier id,DomainIdentifier organizationId,DomainIdentifier siteId,Integer vlanId,Long vni,String name,IpamStatus status,long version,Instant createdAt,Instant updatedAt){public IpamVlan{Objects.requireNonNull(id);Objects.requireNonNull(organizationId);if(vlanId==null&&vni==null)throw new IllegalArgumentException("vlanId or vni is required");if(vlanId!=null&&(vlanId<1||vlanId>4094))throw new IllegalArgumentException("vlanId must be 1..4094");if(vni!=null&&(vni<1||vni>16777215))throw new IllegalArgumentException("vni must be 1..16777215");name=Objects.requireNonNull(name,"name").strip();if(name.isEmpty()||name.length()>160)throw new IllegalArgumentException("name length invalid");Objects.requireNonNull(status);if(version<1)throw new IllegalArgumentException("version must be positive");} public static IpamVlan draft(DomainIdentifier id,DomainIdentifier org,DomainIdentifier site,Integer vlan,Long vni,String name,Instant now){return new IpamVlan(id,org,site,vlan,vni,name,IpamStatus.DRAFT,1,now,now);} public IpamVlan status(IpamStatus target,Instant now){if(status==IpamStatus.RETIRED)throw new IpamConflictException("DDI_VLAN_TERMINAL","retired VLAN is terminal");return new IpamVlan(id,organizationId,siteId,vlanId,vni,name,target,version+1,createdAt,now);}}
