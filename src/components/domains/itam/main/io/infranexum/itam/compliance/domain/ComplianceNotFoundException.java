package io.infranexum.itam.compliance.domain;

/** Raised when a warranty, license, support authorization or coverage cannot be resolved. */
public final class ComplianceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ComplianceNotFoundException() { super("ITAM compliance record not found"); }
}
