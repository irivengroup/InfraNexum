package io.infranexum.itam.partner.domain;

/** Quota denial raised before creating another Partner. */
public final class PartnerQuotaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PartnerQuotaException() { super("itam.partners.max quota exceeded"); }
}
