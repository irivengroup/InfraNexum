package io.infranexum.itam.asset.domain;

/** Stable not-found signal for ITAM assets. */
public final class AssetNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public AssetNotFoundException() { super("ITAM asset was not found"); }
}
