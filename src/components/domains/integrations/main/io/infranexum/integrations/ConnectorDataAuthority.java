package io.infranexum.integrations;

/** Declares which side owns the authoritative value for a connector field or object. */
public enum ConnectorDataAuthority {
    EXTERNAL,
    INFRANEXUM,
    MANUAL
}
