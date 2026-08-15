package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;

public record CreatePoolCommand(DomainIdentifier organizationId,DomainIdentifier networkId,String startAddress,String endAddress,String name){}
