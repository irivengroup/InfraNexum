package io.infranexum.dcim.physical.application;
import io.infranexum.core.contracts.DomainIdentifier;
public record InstallEquipmentCommand(DomainIdentifier organizationId,DomainIdentifier subdivisionId,DomainIdentifier rackId,DomainIdentifier modelId,DomainIdentifier rsotObjectId,DomainIdentifier itamAssetId,String serialNumber,String assetTag,int startU,String face){}
