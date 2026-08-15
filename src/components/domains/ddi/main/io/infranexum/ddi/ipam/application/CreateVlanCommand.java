package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;

public record CreateVlanCommand(DomainIdentifier organizationId,DomainIdentifier siteId,Integer vlanId,Long vni,String name){}
