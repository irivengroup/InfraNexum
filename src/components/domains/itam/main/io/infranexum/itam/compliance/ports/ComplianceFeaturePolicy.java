package io.infranexum.itam.compliance.ports;

/** Effective Core Capabilities policy for PGM-07-E03. */
public interface ComplianceFeaturePolicy { boolean complianceEnabled(); long contractLimit(); }
