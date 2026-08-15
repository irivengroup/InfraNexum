package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;

public record AllocateAddressCommand(DomainIdentifier organizationId,DomainIdentifier vrfId,DomainIdentifier networkId,DomainIdentifier poolId,String requestedAddress,boolean reservation,String hostname,DomainIdentifier rsotObjectId,DomainIdentifier dcimEquipmentId,String purpose){}
