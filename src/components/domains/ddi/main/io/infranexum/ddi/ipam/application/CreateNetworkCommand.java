package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.ddi.ipam.domain.NetworkKind;

public record CreateNetworkCommand(DomainIdentifier organizationId,DomainIdentifier subdivisionId,DomainIdentifier siteId,DomainIdentifier vrfId,DomainIdentifier vlanId,DomainIdentifier parentNetworkId,NetworkKind kind,String cidr,String usage,String trustLevel){}
