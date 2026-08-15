package io.infranexum.dcim.physical.application;
import io.infranexum.core.contracts.DomainIdentifier;
public record ConnectPortsCommand(DomainIdentifier organizationId,DomainIdentifier subdivisionId,DomainIdentifier portAId,DomainIdentifier portBId,String label){}
