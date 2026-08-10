package io.infranexum.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;

/** Verifies payload digest and Ed25519 signature of a signed audit snapshot. */
public final class AuditExportVerifier {
    /** Returns false for an invalid signature or payload without leaking cryptographic details. */
    public boolean verify(SignedAuditExport export, PublicKey verificationKey) {
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(verificationKey, "verificationKey");
        byte[] actualDigest = AuditCanonicalizer.sha256(export.payload()).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedDigest = export.manifest().payloadSha256().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(actualDigest, expectedDigest)) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(verificationKey);
            verifier.update(export.manifest().canonicalText().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(export.signature());
        } catch (GeneralSecurityException error) {
            return false;
        }
    }
}
