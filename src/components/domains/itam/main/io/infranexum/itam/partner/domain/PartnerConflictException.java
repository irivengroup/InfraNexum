package io.infranexum.itam.partner.domain;

/** Stable conflict raised for duplicate, lifecycle, version or idempotency failures. */
public final class PartnerConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public PartnerConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
