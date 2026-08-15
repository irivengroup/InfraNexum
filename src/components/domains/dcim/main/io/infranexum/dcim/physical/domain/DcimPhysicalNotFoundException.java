package io.infranexum.dcim.physical.domain;

/** Raised when a governed DCIM physical object does not exist. */
public final class DcimPhysicalNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public DcimPhysicalNotFoundException(String type) { super(type + " not found"); }
}
