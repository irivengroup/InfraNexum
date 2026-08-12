package io.infranexum.organization.domain;

/** Non-enumerating not-found outcome used at the organization boundary. */
public final class OrganizationNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrganizationNotFoundException() {
        super("organization resource was not found");
    }
}
