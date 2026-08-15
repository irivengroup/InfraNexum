package io.infranexum.ddi.ipam.ports;

import io.infranexum.core.contracts.DomainIdentifier;

/** Validates weak references owned by Organization, DCIM and RSOT. */
public interface IpamReferencePolicy { void requireOrganization(DomainIdentifier organizationId); void requireSubdivisionIfPresent(DomainIdentifier organizationId,DomainIdentifier subdivisionId); void requireSiteIfPresent(DomainIdentifier organizationId,DomainIdentifier subdivisionId,DomainIdentifier siteId); void requireRsotIfPresent(DomainIdentifier organizationId,DomainIdentifier rsotObjectId); void requireDcimEquipmentIfPresent(DomainIdentifier organizationId,DomainIdentifier equipmentId); }
