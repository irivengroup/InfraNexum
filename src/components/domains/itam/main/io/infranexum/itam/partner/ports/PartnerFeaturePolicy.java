package io.infranexum.itam.partner.ports;

/** Effective feature/quota policy supplied by the platform capability catalogue. */
public interface PartnerFeaturePolicy {
    boolean partnerCatalogueEnabled();
    long partnerLimit();
}
