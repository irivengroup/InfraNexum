package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivationManifestVerifierTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CATALOG = "2.0.0-draft.21";
    private CapabilityCatalog capabilityCatalog;
    private QuotaCatalog quotaCatalog;
    private InstallationIdentity identity;
    private KeyPair keyPair;
    private TrustedKey trustedKey;
    private ActivationManifestPayload payload;
    private ActivationManifest manifest;

    @BeforeEach
    void setUp() throws Exception {
        capabilityCatalog = CapabilityCatalog.loadEmbedded(CATALOG);
        quotaCatalog = QuotaCatalog.loadEmbedded(CATALOG);
        DomainIdentifier installationId = idAt(T0);
        identity = new InstallationIdentity(installationId, "v1", "a".repeat(64), T0);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        trustedKey = new TrustedKey("key-1", keyPair.getPublic(), T0.minusSeconds(1), T0.plus(1000, ChronoUnit.DAYS));
        QuotaAllocationPlan plan = quotaCatalog.allocate(InstallationProfile.PRO, AllocationTier.STANDARD, CATALOG, Map.of());
        Set<String> capabilities = capabilityCatalog.codes().stream()
                .filter(code -> capabilityCatalog.find(code).allowedProfiles().contains(InstallationProfile.PRO))
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
        payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, idAt(T0.plusSeconds(1)),
                new CustomerIdentity("customer-1", "Customer One"),
                new ManifestInstallation(identity.installationId(), identity.fingerprintVersion(), identity.fingerprint()),
                InstallationProfile.PRO, AllocationTier.STANDARD, CATALOG,
                plan.limit("rsot.managed_hosts.max"), capabilities, plan.limits(), T0,
                T0.plus(365, ChronoUnit.DAYS), 30, T0, "issuer", 1, trustedKey.keyId());
        manifest = sign(payload);
    }

    @Test
    void validManifestTransitionsAcrossActiveGraceAndHardStop() {
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        ActivationVerificationResult active = verifier.verify(manifest, context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()));
        assertEquals(ActivationUsageState.ACTIVE, active.state());
        assertTrue(active.permitsMutation());
        assertTrue(active.permitsServiceStartup());
        assertEquals(119, active.quotaPlan().limits().size());
        assertEquals(payload.capabilities(), active.entitledCapabilities());

        ActivationVerificationResult grace = verifier.verify(manifest,
                context(payload.validUntil(), new AcceptedSequence(1, payload.activationId()), emptyRevocations()));
        assertEquals(ActivationUsageState.GRACE, grace.state());
        assertTrue(grace.permitsMutation());

        ActivationVerificationResult stopped = verifier.verify(manifest,
                context(grace.graceUntil(), new AcceptedSequence(1, payload.activationId()), emptyRevocations()));
        assertEquals(ActivationUsageState.HARD_STOPPED, stopped.state());
        assertFalse(stopped.permitsMutation());
        assertFalse(stopped.permitsServiceStartup());
    }

    @Test
    void signatureTrustRevocationAndSequenceFailuresAreClosed() throws Exception {
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        byte[] altered = manifest.signatureBytes();
        altered[0] ^= 1;
        assertInvalid(verifier, new ActivationManifest(payload, Base64.getEncoder().encodeToString(altered)),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);

        ActivationValidationContext missingKey = new ActivationValidationContext(identity, "customer-1", InstallationProfile.PRO,
                CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyId -> java.util.Optional.empty(), emptyRevocations(), T0.plusSeconds(1));
        assertInvalid(verifier, manifest, missingKey, EntitlementErrorCodes.ACTIVATION_INVALID);

        TrustedKey expired = new TrustedKey("key-1", keyPair.getPublic(), T0.minusSeconds(10), T0);
        assertInvalid(verifier, manifest, contextWithKey(T0.plusSeconds(1), AcceptedSequence.none(),
                new InMemoryTrustedKeyStore(Map.of("key-1", expired)), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_REVOKED);
        assertInvalid(verifier, manifest, context(T0.plusSeconds(1), AcceptedSequence.none(),
                new InMemoryRevocationRegistry(Map.of("key-1", T0), Map.of())), EntitlementErrorCodes.ACTIVATION_REVOKED);
        assertInvalid(verifier, manifest, context(T0.plusSeconds(1), AcceptedSequence.none(),
                new InMemoryRevocationRegistry(Map.of(), Map.of(payload.activationId(), T0))), EntitlementErrorCodes.ACTIVATION_REVOKED);
        assertInvalid(verifier, manifest,
                context(T0.plusSeconds(1), new AcceptedSequence(2, payload.activationId()), emptyRevocations()),
                EntitlementErrorCodes.ACTIVATION_INVALID);
        assertInvalid(verifier, manifest,
                context(T0.plusSeconds(1), new AcceptedSequence(1, idAt(T0.plusSeconds(2))), emptyRevocations()),
                EntitlementErrorCodes.ACTIVATION_INVALID);
    }

    @Test
    void bindingProfileCatalogueDatesCapabilitiesAndQuotasAreValidated() throws Exception {
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        assertInvalid(verifier, manifest, contextWithCustomer("other"), EntitlementErrorCodes.ACTIVATION_INVALID);
        InstallationIdentity otherIdentity = new InstallationIdentity(idAt(T0.plusSeconds(3)), "v1", "b".repeat(64), T0);
        assertInvalid(verifier, manifest, contextWithIdentity(otherIdentity), EntitlementErrorCodes.ACTIVATION_INVALID);
        ActivationValidationContext enterprise = new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.ENTERPRISE, CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0.plusSeconds(1));
        assertInvalid(verifier, manifest, enterprise, EntitlementErrorCodes.ACTIVATION_INVALID);
        ActivationValidationContext wrongCatalog = new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.PRO, "wrong", capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0.plusSeconds(1));
        assertInvalid(verifier, manifest, wrongCatalog, EntitlementErrorCodes.ACTIVATION_INVALID);
        ActivationValidationContext wrongCapabilityCatalog = new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.PRO, CATALOG, CapabilityCatalog.loadEmbedded("wrong"), quotaCatalog,
                AcceptedSequence.none(), keyStore(), emptyRevocations(), T0.plusSeconds(1));
        assertInvalid(verifier, manifest, wrongCapabilityCatalog, EntitlementErrorCodes.ACTIVATION_INVALID);
        ActivationValidationContext wrongQuotaCatalog = new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.PRO, CATALOG, capabilityCatalog, QuotaCatalog.loadEmbedded("wrong"),
                AcceptedSequence.none(), keyStore(), emptyRevocations(), T0.plusSeconds(1));
        assertInvalid(verifier, manifest, wrongQuotaCatalog, EntitlementErrorCodes.ACTIVATION_INVALID);
        assertInvalid(verifier, manifest, context(T0.minusSeconds(1), AcceptedSequence.none(), emptyRevocations()),
                EntitlementErrorCodes.ACTIVATION_INVALID);

        Map<String, Long> missingQuota = new HashMap<>(payload.quotas());
        missingQuota.remove(missingQuota.keySet().iterator().next());
        assertInvalid(verifier, sign(copy(missingQuota, payload.hostLimit(), payload.capabilities())),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);

        Map<String, Long> fixedChanged = new HashMap<>(payload.quotas());
        fixedChanged.put("deployment.web.nodes_total.max", fixedChanged.get("deployment.web.nodes_total.max") + 1);
        assertInvalid(verifier, sign(copy(fixedChanged, payload.hostLimit(), payload.capabilities())),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);

        assertInvalid(verifier, sign(copy(payload.quotas(), payload.hostLimit() + 1, payload.capabilities())),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);
        Set<String> unknownCapability = new java.util.HashSet<>(payload.capabilities());
        unknownCapability.add("unknown.capability");
        assertInvalid(verifier, sign(copy(payload.quotas(), payload.hostLimit(), unknownCapability)),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);
        Set<String> enterpriseOnly = new java.util.HashSet<>(payload.capabilities());
        enterpriseOnly.add("agent.enabled");
        assertInvalid(verifier, sign(copy(payload.quotas(), payload.hostLimit(), enterpriseOnly)),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()), EntitlementErrorCodes.ACTIVATION_INVALID);
    }

    @Test
    void malformedCapabilityAndInvalidEd25519KeyFailClosed() throws Exception {
        ActivationManifestVerifier verifier = new ActivationManifestVerifier();
        Set<String> malformed = new java.util.HashSet<>(payload.capabilities());
        malformed.add("not valid");
        assertInvalid(verifier, sign(copy(payload.quotas(), payload.hostLimit(), malformed)),
                context(T0.plusSeconds(1), AcceptedSequence.none(), emptyRevocations()),
                EntitlementErrorCodes.ACTIVATION_INVALID);

        java.security.PublicKey invalidEd25519 = new java.security.PublicKey() {
            @Override public String getAlgorithm() { return "EdDSA"; }
            @Override public String getFormat() { return "X.509"; }
            @Override public byte[] getEncoded() { return new byte[] {1, 2, 3}; }
        };
        TrustedKey unusable = new TrustedKey("key-1", invalidEd25519, T0.minusSeconds(1), T0.plusSeconds(100));
        assertThrows(IllegalStateException.class, () -> verifier.verify(manifest,
                contextWithKey(T0.plusSeconds(1), AcceptedSequence.none(),
                        new InMemoryTrustedKeyStore(Map.of("key-1", unusable)), emptyRevocations())));
    }

    @Test
    void validationContextAndResultProtectInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.LITE, CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0));
        assertThrows(IllegalArgumentException.class, () -> new ActivationValidationContext(identity, " ",
                InstallationProfile.PRO, CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0));
        assertThrows(IllegalArgumentException.class, () -> new ActivationValidationContext(identity, "customer-1\n",
                InstallationProfile.PRO, CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0));
        assertThrows(IllegalArgumentException.class, () -> new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.PRO, "catalog\r", capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0));
        assertThrows(IllegalArgumentException.class, () -> new ActivationValidationContext(identity, "customer-1",
                InstallationProfile.PRO, CATALOG, capabilityCatalog, quotaCatalog, AcceptedSequence.none(),
                keyStore(), emptyRevocations(), T0.plusNanos(1)));
        ActivationValidationException error = new ActivationValidationException(EntitlementErrorCodes.ACTIVATION_INVALID, "invalid");
        assertEquals(EntitlementErrorCodes.ACTIVATION_INVALID, error.code());
    }

    private ActivationManifestPayload copy(Map<String, Long> quotas, long hostLimit, Set<String> capabilities) {
        return new ActivationManifestPayload(payload.schema(), payload.activationId(), payload.customer(), payload.installation(),
                payload.profile(), payload.allocationTier(), payload.catalogVersion(), hostLimit, capabilities, quotas,
                payload.validFrom(), payload.validUntil(), payload.gracePeriodDays(), payload.issuedAt(), payload.issuer(),
                payload.sequence(), payload.keyId());
    }

    private ActivationManifest sign(ActivationManifestPayload value) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(value.canonicalBytes());
        return new ActivationManifest(value, Base64.getEncoder().encodeToString(signer.sign()));
    }

    private TrustedKeyStore keyStore() {
        return new InMemoryTrustedKeyStore(Map.of(trustedKey.keyId(), trustedKey));
    }

    private RevocationRegistry emptyRevocations() {
        return new InMemoryRevocationRegistry(Map.of(), Map.of());
    }

    private ActivationValidationContext context(Instant now, AcceptedSequence sequence, RevocationRegistry revocations) {
        return contextWithKey(now, sequence, keyStore(), revocations);
    }

    private ActivationValidationContext contextWithKey(
            Instant now, AcceptedSequence sequence, TrustedKeyStore store, RevocationRegistry revocations) {
        return new ActivationValidationContext(identity, "customer-1", InstallationProfile.PRO, CATALOG,
                capabilityCatalog, quotaCatalog, sequence, store, revocations, now);
    }

    private ActivationValidationContext contextWithCustomer(String customer) {
        return new ActivationValidationContext(identity, customer, InstallationProfile.PRO, CATALOG,
                capabilityCatalog, quotaCatalog, AcceptedSequence.none(), keyStore(), emptyRevocations(), T0.plusSeconds(1));
    }

    private ActivationValidationContext contextWithIdentity(InstallationIdentity installationIdentity) {
        return new ActivationValidationContext(installationIdentity, "customer-1", InstallationProfile.PRO, CATALOG,
                capabilityCatalog, quotaCatalog, AcceptedSequence.none(), keyStore(), emptyRevocations(), T0.plusSeconds(1));
    }

    private static void assertInvalid(
            ActivationManifestVerifier verifier,
            ActivationManifest value,
            ActivationValidationContext context,
            io.infranexum.core.contracts.DomainErrorCode expectedCode) {
        ActivationValidationException error = assertThrows(ActivationValidationException.class,
                () -> verifier.verify(value, context));
        assertEquals(expectedCode, error.code());
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }
}
