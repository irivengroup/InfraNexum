package io.infranexum.dcim.facility.domain;

/** Raised when a DCIM facility node is not visible in the authoritative repository. */
public final class FacilityNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public FacilityNotFoundException() { super("facility node was not found"); }
}
