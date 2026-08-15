package io.infranexum.ddi.ipam.domain;

/** Raised when a governed IPAM object cannot be resolved. */
public final class IpamNotFoundException extends RuntimeException { private static final long serialVersionUID=1L; public IpamNotFoundException(String subject){super(subject+" not found");} }
