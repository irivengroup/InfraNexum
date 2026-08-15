package io.infranexum.ddi.ipam.ports;

/** Capability and quota boundary for DDI/IPAM. */
public interface IpamFeaturePolicy { boolean ipamEnabled(); long vrfLimit(); long vlanLimit(); long prefixLimit(); long addressLimit(); }
