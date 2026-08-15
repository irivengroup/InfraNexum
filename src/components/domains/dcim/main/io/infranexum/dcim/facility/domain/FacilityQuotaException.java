package io.infranexum.dcim.facility.domain;

/** Raised when a profile allocation limit prevents creation of a new DCIM object. */
public final class FacilityQuotaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public FacilityQuotaException(FacilityKind kind) { super("DCIM " + kind.wireValue() + " quota exceeded"); }
}
