package io.infranexum.dcim.physical.application;
import io.infranexum.core.contracts.DomainIdentifier;
public record CreateRackCommand(DomainIdentifier organizationId,DomainIdentifier subdivisionId,DomainIdentifier roomId,String code,String displayName,int heightU,int widthMm,int depthMm){}
