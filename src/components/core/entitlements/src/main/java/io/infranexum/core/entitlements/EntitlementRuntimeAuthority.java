package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;

/**
 * Computes and persists the only runtime entitlement decision used by Server startup and mutations.
 * Local serialization prevents competing refresh/import operations inside one Server process; database
 * uniqueness and transactions remain the cross-process authority.
 */
public final class EntitlementRuntimeAuthority {
    private final EntitlementRuntimeRepository repository;
    private final IndependentIntegrityProofStore independentStore;
    private final ActivationManifestVerifier verifier;
    private final ActivationContextFactory contextFactory;
    private final ActivationManifestCodec codec;
    private final TrustedTimeGuard timeGuard;
    private final LiteEvaluationPolicy litePolicy;
    private final EntitlementGuard guard;
    private final SecretKey integrityKey;
    private final Clock clock;
    private final AtomicReference<EntitlementRuntimeStatus> current = new AtomicReference<>();

    public EntitlementRuntimeAuthority(
            EntitlementRuntimeRepository repository,
            IndependentIntegrityProofStore independentStore,
            ActivationManifestVerifier verifier,
            ActivationContextFactory contextFactory,
            ActivationManifestCodec codec,
            TrustedTimeGuard timeGuard,
            LiteEvaluationPolicy litePolicy,
            EntitlementGuard guard,
            SecretKey integrityKey,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.independentStore = Objects.requireNonNull(independentStore, "independentStore");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.timeGuard = Objects.requireNonNull(timeGuard, "timeGuard");
        this.litePolicy = Objects.requireNonNull(litePolicy, "litePolicy");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.integrityKey = Objects.requireNonNull(integrityKey, "integrityKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized EntitlementRuntimeStatus initializeAndRequireStartup(InstallationProfile installedProfile) {
        EntitlementRuntimeStatus status = refreshInternal(installedProfile);
        requireStartup(status);
        current.set(status);
        return status;
    }

    public synchronized EntitlementRuntimeStatus refresh(InstallationProfile installedProfile) {
        EntitlementRuntimeStatus status = refreshInternal(installedProfile);
        current.set(status);
        return status;
    }

    public EntitlementRuntimeStatus currentStatus() {
        EntitlementRuntimeStatus status = current.get();
        if (status == null) {
            throw new EntitlementRuntimeUnavailableException("entitlement runtime authority is not initialized");
        }
        return status;
    }

    public void requireMutation() {
        EntitlementRuntimeStatus status = currentStatus();
        if (status.mutationPermitted()) {
            return;
        }
        if (status.phase() == EntitlementRuntimePhase.CONVERSION_REQUIRED) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.LITE_CONVERSION_REQUIRED,
                    "Lite usage is restricted to non-mutating operations");
        }
        throw new EntitlementAccessException(
                status.profile() == InstallationProfile.LITE
                        ? EntitlementErrorCodes.LITE_HARD_STOPPED
                        : EntitlementErrorCodes.ACTIVATION_EXPIRED,
                "entitlement state does not permit mutations");
    }

    public ActivationVerificationResult preflight(ActivationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        InstallationIdentity identity = requireIdentity();
        Instant now = now();
        AcceptedSequence sequence = repository.acceptedSequence(identity);
        return verifier.verify(manifest, contextFactory.create(identity, sequence, now));
    }

    private EntitlementRuntimeStatus refreshInternal(InstallationProfile installedProfile) {
        Objects.requireNonNull(installedProfile, "installedProfile");
        InstallationIdentity identity = requireIdentity();
        Instant now = now();
        EntitlementStateRecord state = repository.entitlementState(identity).orElse(null);
        if (state == null) {
            if (installedProfile != InstallationProfile.LITE) {
                throw new EntitlementAccessException(
                        EntitlementErrorCodes.ACTIVATION_REQUIRED,
                        "Pro and Enterprise require an accepted activation manifest before Server startup");
            }
            return initializeLite(identity, now);
        }
        if (state.profile() != installedProfile) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.ACTIVATION_INVALID,
                    "installed profile differs from the durable entitlement profile");
        }
        IntegrityProof previousIndependent = independentStore.load(identity)
                .orElseThrow(() -> new ClockRollbackException("independent temporal evidence is missing"));
        IntegrityProof previousDatabase = repository.databaseProof(identity)
                .orElseThrow(() -> new ClockRollbackException("database temporal evidence is missing"));
        IntegrityProofPair next = timeGuard.observe(
                new IntegrityProofPair(previousDatabase, previousIndependent), identity, now, integrityKey);
        EntitlementRuntimeStatus decision = installedProfile == InstallationProfile.LITE
                ? evaluateLite(identity, state, next.databaseProof(), now)
                : evaluatePaid(identity, state, now);
        persistAdvancedDecision(identity, previousIndependent, next, decision, now);
        return decision;
    }

    private EntitlementRuntimeStatus initializeLite(InstallationIdentity identity, Instant now) {
        IntegrityProofPair initial = timeGuard.initialize(identity, now, integrityKey);
        independentStore.store(initial.independentProof());
        try {
            repository.initializeLite(identity, initial.databaseProof(), now);
        } catch (RuntimeException failure) {
            compensate(identity, null, failure);
            throw failure;
        }
        LiteEvaluation evaluation = litePolicy.evaluate(now, now);
        return EntitlementRuntimeStatus.from(identity, AllocationTier.STANDARD, evaluation);
    }

    private EntitlementRuntimeStatus evaluateLite(
            InstallationIdentity identity,
            EntitlementStateRecord state,
            IntegrityProof proof,
            Instant now) {
        if (!proof.evaluationStartedAt().equals(state.evaluationStartedAt())) {
            throw new ClockRollbackException("Lite origin differs between entitlement state and integrity proof");
        }
        return EntitlementRuntimeStatus.from(
                identity, AllocationTier.STANDARD, litePolicy.evaluate(proof.evaluationStartedAt(), now));
    }

    private EntitlementRuntimeStatus evaluatePaid(
            InstallationIdentity identity, EntitlementStateRecord state, Instant now) {
        String document = repository.acceptedManifestDocument(identity)
                .orElseThrow(() -> new EntitlementAccessException(
                        EntitlementErrorCodes.ACTIVATION_INVALID,
                        "accepted activation document is unavailable; re-import is required"));
        ActivationManifest manifest = codec.decode(document);
        if (!manifest.payload().activationId().equals(state.acceptedActivationId())) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.ACTIVATION_INVALID,
                    "accepted activation document differs from durable entitlement state");
        }
        ActivationVerificationResult result = verifier.verify(
                manifest, contextFactory.create(identity, state.acceptedSequence(), now));
        return new EntitlementRuntimeStatus(
                identity.installationId(), result.payload().profile(), result.payload().allocationTier(),
                switch (result.state()) {
                    case ACTIVE -> EntitlementRuntimePhase.ACTIVE;
                    case GRACE -> EntitlementRuntimePhase.GRACE;
                    case HARD_STOPPED -> EntitlementRuntimePhase.HARD_STOPPED;
                },
                now, null, null, null, result.payload().validUntil(), result.graceUntil(),
                result.payload().sequence(), result.payload().activationId(),
                result.payload().capabilities(), result.payload().quotas(),
                result.permitsServiceStartup(), result.permitsMutation());
    }

    private void persistAdvancedDecision(
            InstallationIdentity identity,
            IntegrityProof previousIndependent,
            IntegrityProofPair next,
            EntitlementRuntimeStatus decision,
            Instant now) {
        independentStore.store(next.independentProof());
        try {
            repository.updateRuntimeState(identity, decision, next.databaseProof(), now);
        } catch (RuntimeException failure) {
            compensate(identity, previousIndependent, failure);
            throw failure;
        }
    }

    private void compensate(
            InstallationIdentity identity, IntegrityProof previousIndependent, RuntimeException original) {
        try {
            if (previousIndependent == null) {
                independentStore.delete(identity);
            } else {
                independentStore.store(previousIndependent);
            }
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
            throw new IllegalStateException(
                    "entitlement refresh failed and temporal evidence compensation failed", original);
        }
    }

    private void requireStartup(EntitlementRuntimeStatus status) {
        if (status.serviceStartupPermitted()) {
            return;
        }
        if (status.profile() == InstallationProfile.LITE) {
            throw new EntitlementAccessException(
                    EntitlementErrorCodes.LITE_HARD_STOPPED,
                    "Lite usage reached the day-210 hard-stop boundary");
        }
        throw new EntitlementAccessException(
                EntitlementErrorCodes.ACTIVATION_EXPIRED,
                "activation and its fixed grace period have expired");
    }

    private InstallationIdentity requireIdentity() {
        return repository.installationIdentity()
                .orElseThrow(() -> new IllegalStateException("installation identity is not initialized"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
