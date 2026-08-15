package io.infranexum.itam.partner.domain;

/** Stable not-found failure for the ITAM Partner boundary. */
public final class PartnerNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PartnerNotFoundException() { super("partner not found"); }
}
