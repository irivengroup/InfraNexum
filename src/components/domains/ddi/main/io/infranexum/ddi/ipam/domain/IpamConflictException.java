package io.infranexum.ddi.ipam.domain;

/** Raised when an IPAM mutation violates allocation, overlap or lifecycle invariants. */
public final class IpamConflictException extends RuntimeException { private static final long serialVersionUID=1L; private final String code; public IpamConflictException(String code,String message){super(message);this.code=code;} public String code(){return code;} }
