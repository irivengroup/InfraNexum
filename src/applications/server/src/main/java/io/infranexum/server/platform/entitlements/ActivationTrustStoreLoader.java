package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.InMemoryTrustedKeyStore;
import io.infranexum.core.entitlements.TrustedKey;
import io.infranexum.core.entitlements.TrustedKeyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Loads a versioned, public-key-only Ed25519 trust store from an external file. */
public final class ActivationTrustStoreLoader {
    private static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper mapper;

    public ActivationTrustStoreLoader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public TrustedKeyStore load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            byte[] content = Files.readAllBytes(path);
            if (content.length == 0 || content.length > MAX_BYTES) {
                throw new IllegalArgumentException("activation trust store must contain 1 byte to 1 MiB");
            }
            JsonNode parsed = mapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (!(parsed instanceof ObjectNode root) || root.size() != 2
                    || !"infranexum.activation-trust-store/v1".equals(text(root, "schema"))) {
                throw new IllegalArgumentException("invalid activation trust store schema");
            }
            JsonNode keys = root.get("keys");
            if (keys == null || !keys.isArray() || keys.size() == 0 || keys.size() > 256) {
                throw new IllegalArgumentException("activation trust store must contain 1 to 256 keys");
            }
            Map<String, TrustedKey> trusted = new HashMap<>();
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            for (JsonNode value : keys) {
                if (!(value instanceof ObjectNode key) || key.size() != 5) {
                    throw new IllegalArgumentException("invalid activation trust key entry");
                }
                String keyId = text(key, "key_id");
                if (!"Ed25519".equals(text(key, "algorithm"))) {
                    throw new IllegalArgumentException("only Ed25519 activation keys are supported");
                }
                byte[] encoded = Base64.getDecoder().decode(text(key, "public_key_x509_base64"));
                TrustedKey item = new TrustedKey(
                        keyId,
                        factory.generatePublic(new X509EncodedKeySpec(encoded)),
                        Instant.parse(text(key, "valid_from")),
                        Instant.parse(text(key, "valid_until")));
                if (trusted.putIfAbsent(keyId, item) != null) {
                    throw new IllegalArgumentException("duplicate activation trust key: " + keyId);
                }
            }
            return new InMemoryTrustedKeyStore(trusted);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("cannot load activation trust store", error);
        }
    }

    private static String text(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isString() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return node.asText();
    }
}
