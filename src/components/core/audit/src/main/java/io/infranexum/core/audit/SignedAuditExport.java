package io.infranexum.core.audit;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Signed deterministic export bundle and its independently verifiable metadata. */
public final class SignedAuditExport {
    private final AuditExportManifest manifest;
    private final byte[] payload;
    private final byte[] signature;
    private final byte[] archive;

    public SignedAuditExport(AuditExportManifest manifest, byte[] payload, byte[] signature, byte[] archive) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.signature = Objects.requireNonNull(signature, "signature").clone();
        this.archive = Objects.requireNonNull(archive, "archive").clone();
        if (this.payload.length == 0 || this.signature.length == 0 || this.archive.length == 0) {
            throw new IllegalArgumentException("audit export artifacts must not be empty");
        }
    }

    public AuditExportManifest manifest() { return manifest; }
    public byte[] payload() { return payload.clone(); }
    public byte[] signature() { return signature.clone(); }
    public String signatureBase64() { return Base64.getEncoder().encodeToString(signature); }
    public byte[] archive() { return archive.clone(); }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SignedAuditExport that)) return false;
        return manifest.equals(that.manifest)
                && Arrays.equals(payload, that.payload)
                && Arrays.equals(signature, that.signature)
                && Arrays.equals(archive, that.archive);
    }

    @Override
    public int hashCode() {
        int result = manifest.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Arrays.hashCode(signature);
        result = 31 * result + Arrays.hashCode(archive);
        return result;
    }
}
