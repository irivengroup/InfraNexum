package io.infranexum.core.entitlements;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CanonicalJsonAndValueObjectsTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void canonicalJsonSortsEscapesAndRejectsUnsupportedValues() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("z", java.util.Arrays.asList(true, null, 2L));
        value.put("a", "x\n\t\"\\\u0001");
        assertEquals("{\"a\":\"x\\n\\t\\\"\\\\\\u0001\",\"z\":[true,null,2]}", CanonicalJson.string(value));
        assertArrayEquals(CanonicalJson.string(value).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                CanonicalJson.bytes(value));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(Map.of("x", 1.25d)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(Map.of("x", java.math.BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(new Object()));
        Map<Object, Object> invalidKey = new LinkedHashMap<>();
        invalidKey.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.string(invalidKey));
    }

    @Test
    void installationFingerprintIsOrderIndependentAndStrict() {
        String first = InstallationFingerprint.compute("v1", Map.of("B", "2", "a", "1"));
        String second = InstallationFingerprint.compute("v1", Map.of("a", "1", "b", "2"));
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertThrows(NullPointerException.class, () -> InstallationFingerprint.compute(null, Map.of("a", "1")));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("1", Map.of("a", "1")));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", Map.of("a", "\n")));
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("A", "1");
        duplicate.put("a", "2");
        assertThrows(IllegalArgumentException.class, () -> InstallationFingerprint.compute("v1", duplicate));
    }

    @Test
    void identityAndManifestValueObjectsRejectInvalidContracts() throws Exception {
        DomainIdentifier id = idAt(T0);
        String fp = "a".repeat(64);
        InstallationIdentity identity = new InstallationIdentity(id, "v1", fp, T0);
        assertEquals(id, identity.installationId());
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "1", fp, T0));
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "v1", "bad", T0));
        assertThrows(IllegalArgumentException.class, () -> new InstallationIdentity(id, "v1", fp, T0.plusNanos(1)));

        CustomerIdentity customer = new CustomerIdentity("c1", "Customer");
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity(" ", "Customer"));
        assertThrows(IllegalArgumentException.class, () -> new CustomerIdentity("c", "x".repeat(256)));
        ManifestInstallation binding = new ManifestInstallation(id, "v1", fp);
        assertTrue(binding.matches(identity));
        assertThrows(IllegalArgumentException.class, () -> new ManifestInstallation(id, "bad", fp));
        assertThrows(IllegalArgumentException.class, () -> new ManifestInstallation(id, "v1", "x"));

        Map<String, Long> quotas = Map.of("rsot.managed_hosts.max", 10L);
        ActivationManifestPayload payload = new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 10, Set.of("iam.local-auth"), quotas,
                T0, T0.plusSeconds(60), 30, T0, "issuer", 1, "key");
        assertFalse(payload.canonicalValue().containsKey("signature"));
        assertTrue(new String(payload.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"profile\":\"pro\""));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                "wrong", id, customer, binding, InstallationProfile.PRO, AllocationTier.STANDARD,
                "catalog", 10, Set.of(), quotas, T0, T0.plusSeconds(1), 30, T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.LITE,
                AllocationTier.STANDARD, "catalog", 10, Set.of(), quotas, T0, T0.plusSeconds(1), 30,
                T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.ULTIMATE, "catalog", 10, Set.of(), quotas, T0, T0.plusSeconds(1), 30,
                T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", -1, Set.of(), quotas, T0, T0.plusSeconds(1), 30,
                T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(" "), quotas, T0, T0.plusSeconds(1), 30,
                T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(), Map.of("x", -1L), T0,
                T0.plusSeconds(1), 30, T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(), quotas, T0, T0, 30, T0,
                "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(), quotas, T0, T0.plusSeconds(1), 29,
                T0, "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(), quotas, T0, T0.plusSeconds(1), 30,
                T0.plusSeconds(1), "issuer", 1, "key"));
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifestPayload(
                ActivationManifestPayload.SCHEMA, id, customer, binding, InstallationProfile.PRO,
                AllocationTier.STANDARD, "catalog", 1, Set.of(), quotas, T0, T0.plusSeconds(1), 30,
                T0, "issuer", 0, "key"));

        String signature = Base64.getEncoder().encodeToString(new byte[64]);
        ActivationManifest manifest = new ActivationManifest(payload, signature);
        assertEquals(64, manifest.signatureBytes().length);
        assertThrows(IllegalArgumentException.class, () -> new ActivationManifest(payload, "%%%"));
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationManifest(payload, Base64.getEncoder().encodeToString(new byte[63])));

        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedKey key = new TrustedKey("key", pair.getPublic(), T0, T0.plusSeconds(10));
        assertTrue(key.isValidAt(T0));
        assertFalse(key.isValidAt(T0.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedKey("", pair.getPublic(), T0, T0.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedKey("k", pair.getPublic(), T0, T0));
        InMemoryTrustedKeyStore store = new InMemoryTrustedKeyStore(Map.of("key", key));
        assertSame(key, store.find("key").orElseThrow());
        assertTrue(store.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTrustedKeyStore(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryTrustedKeyStore(Map.of("other", key)));
    }

    @Test
    void sequenceAndRevocationsAreMonotonicAndEffective() {
        DomainIdentifier id = idAt(T0);
        AcceptedSequence none = AcceptedSequence.none();
        assertTrue(none.accepts(1, id));
        AcceptedSequence accepted = new AcceptedSequence(2, id);
        assertTrue(accepted.accepts(2, id));
        assertTrue(accepted.accepts(3, id));
        assertFalse(accepted.accepts(1, id));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedSequence(-1, null));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedSequence(0, id));
        assertThrows(NullPointerException.class, () -> new AcceptedSequence(1, null));

        InMemoryRevocationRegistry registry = new InMemoryRevocationRegistry(
                Map.of("key", T0.plusSeconds(5)), Map.of(id, T0.plusSeconds(10)));
        assertFalse(registry.isKeyRevoked("key", T0.plusSeconds(4)));
        assertTrue(registry.isKeyRevoked("key", T0.plusSeconds(5)));
        assertFalse(registry.isActivationRevoked(id, T0.plusSeconds(9)));
        assertTrue(registry.isActivationRevoked(id, T0.plusSeconds(10)));
    }

    private static DomainIdentifier idAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom()).next();
    }
}
