package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;

public record CreateVrfCommand(DomainIdentifier organizationId,String code,String displayName,String routeDistinguisher){}
