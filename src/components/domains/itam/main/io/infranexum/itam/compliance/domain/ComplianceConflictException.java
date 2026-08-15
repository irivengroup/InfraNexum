package io.infranexum.itam.compliance.domain;

/** Stable business conflict exposed by PGM-07-E03 boundaries. */
public final class ComplianceConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public ComplianceConflictException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        this.code = code;
    }
    public String code() { return code; }
}
