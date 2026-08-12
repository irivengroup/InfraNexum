package io.infranexum.organization.domain;

/** Raised when an organization lifecycle transition violates the normative state machine. */
public final class OrganizationStateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final OrganizationState source;
    private final OrganizationState target;

    public OrganizationStateException(OrganizationState source, OrganizationState target) {
        super("invalid organization state transition: " + source + " -> " + target);
        this.source = source;
        this.target = target;
    }

    public OrganizationState source() {
        return source;
    }

    public OrganizationState target() {
        return target;
    }
}
