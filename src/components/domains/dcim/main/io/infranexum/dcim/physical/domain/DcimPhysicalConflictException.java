package io.infranexum.dcim.physical.domain;

/** Business conflict raised by PGM-07-E05 physical occupancy and cabling rules. */
public final class DcimPhysicalConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public DcimPhysicalConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
