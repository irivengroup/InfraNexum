package io.infranexum.core.entitlements;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Verifies dual temporal evidence and advances it monotonically before runtime decisions. */
public final class TrustedTimeGuard {
    public IntegrityProofPair initialize(InstallationIdentity identity, Instant startedAt, SecretKey integrityKey) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(startedAt, "startedAt");
        InstallationIdentity.requireWholeSecond(startedAt, "startedAt");
        IntegrityProof proof = sign(identity, startedAt, startedAt, 1, integrityKey);
        return new IntegrityProofPair(proof, proof);
    }

    public IntegrityProofPair observe(
            IntegrityProofPair current,
            InstallationIdentity identity,
            Instant now,
            SecretKey integrityKey) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(now, "now");
        InstallationIdentity.requireWholeSecond(now, "now");
        verify(current.databaseProof(), identity, integrityKey);
        verify(current.independentProof(), identity, integrityKey);
        if (!sameEvidence(current.databaseProof(), current.independentProof())) {
            throw new ClockRollbackException("database and independent temporal evidence diverge");
        }
        IntegrityProof proof = current.databaseProof();
        if (now.isBefore(proof.lastReliableAt())) {
            throw new ClockRollbackException("current time is before the last reliable observation");
        }
        long nextGeneration;
        try {
            nextGeneration = Math.incrementExact(proof.generation());
        } catch (ArithmeticException error) {
            throw new ClockRollbackException("trusted time generation is exhausted");
        }
        IntegrityProof next = sign(identity, proof.evaluationStartedAt(), now, nextGeneration, integrityKey);
        return new IntegrityProofPair(next, next);
    }

    public void verify(IntegrityProof proof, InstallationIdentity identity, SecretKey integrityKey) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(identity, "identity");
        if (!proof.installationId().equals(identity.installationId())
                || !proof.fingerprint().equals(identity.fingerprint())) {
            throw new ClockRollbackException("trusted time proof is bound to another installation");
        }
        byte[] expected = hmac(proof.unsignedValue(), integrityKey);
        byte[] actual = Base64.getDecoder().decode(proof.mac());
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ClockRollbackException("trusted time proof integrity verification failed");
        }
    }

    private static IntegrityProof sign(
            InstallationIdentity identity,
            Instant evaluationStartedAt,
            Instant lastReliableAt,
            long generation,
            SecretKey integrityKey) {
        IntegrityProof unsigned = new IntegrityProof(
                identity.installationId(), identity.fingerprint(), evaluationStartedAt, lastReliableAt,
                generation, Base64.getEncoder().encodeToString(new byte[32]));
        String mac = Base64.getEncoder().encodeToString(hmac(unsigned.unsignedValue(), integrityKey));
        return new IntegrityProof(
                identity.installationId(), identity.fingerprint(), evaluationStartedAt, lastReliableAt, generation, mac);
    }

    private static byte[] hmac(Object value, SecretKey key) {
        Objects.requireNonNull(key, "integrityKey");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(CanonicalJson.bytes(value));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", error);
        }
    }

    private static boolean sameEvidence(IntegrityProof left, IntegrityProof right) {
        // verify(...) has already proven installation binding and HMAC integrity for both proofs.
        // Only the mutable temporal evidence can still diverge at this point.
        return left.evaluationStartedAt().equals(right.evaluationStartedAt())
                && left.lastReliableAt().equals(right.lastReliableAt())
                && left.generation() == right.generation();
    }
}
