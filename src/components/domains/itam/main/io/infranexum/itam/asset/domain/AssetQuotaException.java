package io.infranexum.itam.asset.domain;

/** Hard allocation boundary for itam.assets.max. */
public final class AssetQuotaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public AssetQuotaException() { super("itam.assets.max quota exceeded"); }
}
