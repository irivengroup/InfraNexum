package io.infranexum.ddi.ipam.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;

/** Inclusive allocatable range inside an active subnet with a durable allocator cursor. */
public record IpamPool(DomainIdentifier id,DomainIdentifier organizationId,DomainIdentifier networkId,String startAddress,String endAddress,String allocationCursor,String name,IpamStatus status,long version,Instant createdAt,Instant updatedAt){public IpamPool{Objects.requireNonNull(id);Objects.requireNonNull(organizationId);Objects.requireNonNull(networkId);startAddress=IpCidr.canonicalAddress(startAddress);endAddress=IpCidr.canonicalAddress(endAddress);allocationCursor=IpCidr.canonicalAddress(allocationCursor);name=Objects.requireNonNull(name,"name").strip();if(name.isEmpty()||name.length()>160)throw new IllegalArgumentException("name length invalid");Objects.requireNonNull(status);if(version<1)throw new IllegalArgumentException("version must be positive");}public static IpamPool active(DomainIdentifier id,DomainIdentifier org,DomainIdentifier net,String start,String end,String name,Instant now){return new IpamPool(id,org,net,start,end,start,name,IpamStatus.ACTIVE,1,now,now);} public IpamPool advance(String next,Instant now){return new IpamPool(id,organizationId,networkId,startAddress,endAddress,next,name,status,version+1,createdAt,now);}}
