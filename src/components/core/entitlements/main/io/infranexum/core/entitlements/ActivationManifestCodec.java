package io.infranexum.core.entitlements;

/** Decodes a bounded activation document at the server boundary. */
@FunctionalInterface
public interface ActivationManifestCodec {
    ActivationManifest decode(String document);
}
