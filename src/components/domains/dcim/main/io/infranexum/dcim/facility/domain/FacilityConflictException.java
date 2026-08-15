package io.infranexum.dcim.facility.domain;

/** Stable business conflict raised by the DCIM physical hierarchy. */
public final class FacilityConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public FacilityConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
