package io.infranexum.dcim.physical.ports;
import io.infranexum.core.contracts.DomainIdentifier;
/** Cross-context validators; no DCIM foreign key is created to these authorities. */
public interface DcimPhysicalReferencePolicy {
 void requireScope(DomainIdentifier organizationId, DomainIdentifier subdivisionId);
 void requireActiveRoom(DomainIdentifier roomId, DomainIdentifier organizationId, DomainIdentifier subdivisionId);
 void requireManufacturer(DomainIdentifier manufacturerPartnerId, DomainIdentifier organizationId);
 void requireRsotObject(DomainIdentifier rsotObjectId, DomainIdentifier organizationId);
 void requireItamAssetIfPresent(DomainIdentifier itamAssetId, DomainIdentifier organizationId);
}
