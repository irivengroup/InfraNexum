package io.infranexum.core.entitlements;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.SecretKey;

/** Coordinates offline verification, dual temporal evidence, compensation and durable acceptance. */
public final class ActivationImportCoordinator {
    private final ActivationOperationalRepository repository;
    private final IndependentIntegrityProofStore independentStore;
    private final ActivationManifestVerifier verifier;
    private final ActivationContextFactory contextFactory;
    private final TrustedTimeGuard timeGuard;
    private final SecretKey integrityKey;
    private final Clock clock;

    public ActivationImportCoordinator(ActivationOperationalRepository repository,
            IndependentIntegrityProofStore independentStore, ActivationManifestVerifier verifier,
            ActivationContextFactory contextFactory, TrustedTimeGuard timeGuard,
            SecretKey integrityKey, Clock clock) {
        this.repository=Objects.requireNonNull(repository,"repository");
        this.independentStore=Objects.requireNonNull(independentStore,"independentStore");
        this.verifier=Objects.requireNonNull(verifier,"verifier");
        this.contextFactory=Objects.requireNonNull(contextFactory,"contextFactory");
        this.timeGuard=Objects.requireNonNull(timeGuard,"timeGuard");
        this.integrityKey=Objects.requireNonNull(integrityKey,"integrityKey");
        this.clock=Objects.requireNonNull(clock,"clock");
    }

    public ActivationImportResult importManifest(ActivationManifest manifest) {
        InstallationIdentity identity = repository.installationIdentity()
                .orElseThrow(() -> new IllegalStateException("installation identity is not initialized"));
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        AcceptedSequence sequence = repository.acceptedSequence(identity);
        ActivationVerificationResult verified = verifier.verify(manifest, contextFactory.create(identity, sequence, now));
        IntegrityProof previousIndependent = independentStore.load(identity).orElse(null);
        IntegrityProof previousDatabase = repository.databaseProof(identity).orElse(null);
        IntegrityProofPair next = advance(identity, now, previousDatabase, previousIndependent);
        independentStore.store(next.independentProof());
        try {
            repository.accept(identity, manifest, verified, next.databaseProof(), now);
        } catch (RuntimeException failure) {
            compensate(identity, previousIndependent, failure);
            throw failure;
        }
        return new ActivationImportResult(verified.state(), manifest.payload().sequence(), verified.graceUntil());
    }

    private IntegrityProofPair advance(InstallationIdentity identity, Instant now,
            IntegrityProof databaseProof, IntegrityProof independentProof) {
        if (databaseProof == null && independentProof == null) return timeGuard.initialize(identity, now, integrityKey);
        if (databaseProof == null || independentProof == null) throw new ClockRollbackException("temporal evidence is incomplete");
        return timeGuard.observe(new IntegrityProofPair(databaseProof, independentProof), identity, now, integrityKey);
    }

    private void compensate(InstallationIdentity identity, IntegrityProof previous, RuntimeException original) {
        try {
            if (previous == null) independentStore.delete(identity); else independentStore.store(previous);
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
            throw new IllegalStateException("activation import failed and temporal evidence compensation failed", original);
        }
    }
}
