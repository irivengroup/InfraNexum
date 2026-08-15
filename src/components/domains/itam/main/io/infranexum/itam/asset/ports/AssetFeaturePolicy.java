package io.infranexum.itam.asset.ports;

/** Effective capability/quota policy supplied by Core Capabilities. */
public interface AssetFeaturePolicy {
    boolean assetLifecycleEnabled();
    long assetLimit();
}
