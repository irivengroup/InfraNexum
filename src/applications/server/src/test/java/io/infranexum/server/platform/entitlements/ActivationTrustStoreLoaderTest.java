package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ActivationTrustStoreLoaderTest {
    @TempDir Path directory;

    @Test
    void loadsPublicEd25519KeysAndRejectsInvalidContracts() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String encoded = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String entry = """
                {"key_id":"key-1","algorithm":"Ed25519","public_key_x509_base64":"%s",
                 "valid_from":"2026-01-01T00:00:00Z","valid_until":"2027-01-01T00:00:00Z"}
                """.formatted(encoded).replace("\n", "");
        Path trust = directory.resolve("trust.json");
        Files.writeString(trust, "{\"schema\":\"infranexum.activation-trust-store/v1\",\"keys\":[" + entry + "]}");
        var store = new ActivationTrustStoreLoader(new ObjectMapper()).load(trust);
        assertTrue(store.find("key-1").isPresent());
        assertTrue(java.util.Set.of("EdDSA", "Ed25519").contains(
                store.find("key-1").orElseThrow().publicKey().getAlgorithm()));

        Files.writeString(trust, "{\"schema\":\"wrong\",\"keys\":[" + entry + "]}");
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationTrustStoreLoader(new ObjectMapper()).load(trust));
        Files.writeString(trust, "{\"schema\":\"infranexum.activation-trust-store/v1\",\"keys\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationTrustStoreLoader(new ObjectMapper()).load(trust));
        Files.writeString(trust, "{\"schema\":\"infranexum.activation-trust-store/v1\",\"keys\":["
                + entry + "," + entry + "]}");
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationTrustStoreLoader(new ObjectMapper()).load(trust));
        assertThrows(NullPointerException.class, () -> new ActivationTrustStoreLoader(null));
    }
}
