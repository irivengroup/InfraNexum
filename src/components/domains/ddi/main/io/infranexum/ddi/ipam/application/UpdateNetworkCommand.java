package io.infranexum.ddi.ipam.application;

import io.infranexum.core.contracts.DomainIdentifier;

/** Mutable network metadata. CIDR, VRF and parentage remain immutable to protect routing-space integrity. */
public record UpdateNetworkCommand(DomainIdentifier vlanId,String usage,String trustLevel) {}
