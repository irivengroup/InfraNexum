package io.infranexum.dcim.physical.ports;
/** Capability/quota policy for PGM-07-E05. */
public interface DcimPhysicalFeaturePolicy { boolean physicalEnabled(); long rackLimit(); long portLimit(); long connectionLimit(); }
