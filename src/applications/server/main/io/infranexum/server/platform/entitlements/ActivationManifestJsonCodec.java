package io.infranexum.server.platform.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.ActivationManifest;
import io.infranexum.core.entitlements.ActivationManifestCodec;
import io.infranexum.core.entitlements.ActivationManifestPayload;
import io.infranexum.core.entitlements.CustomerIdentity;
import io.infranexum.core.entitlements.ManifestInstallation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict Jackson 3 decoder for the canonical activation-manifest/v2 contract. */
public final class ActivationManifestJsonCodec implements ActivationManifestCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "activation_id", "customer", "installation", "profile", "allocation_tier",
            "catalog_version", "host_limit", "capabilities", "quotas", "valid_from", "valid_until",
            "grace_period_days", "issued_at", "issuer", "sequence", "key_id", "signature");
    private static final Set<String> CUSTOMER_FIELDS = Set.of("customer_id", "legal_name");
    private static final Set<String> INSTALLATION_FIELDS = Set.of(
            "installation_id", "fingerprint_version", "fingerprint");

    private final ObjectMapper mapper;
    private final int maxManifestBytes;

    public ActivationManifestJsonCodec(ObjectMapper mapper, int maxManifestBytes) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (maxManifestBytes < 1024 || maxManifestBytes > 4_194_304) {
            throw new IllegalArgumentException("maxManifestBytes must be between 1 KiB and 4 MiB");
        }
        this.maxManifestBytes = maxManifestBytes;
    }

    @Override
    public ActivationManifest decode(String document) {
        Objects.requireNonNull(document, "document");
        if (document.getBytes(StandardCharsets.UTF_8).length > maxManifestBytes) {
            throw new IllegalArgumentException("activation manifest exceeds the configured size limit");
        }
        try {
            ObjectNode root = requireObject(mapper.readTree(document), "manifest", ROOT_FIELDS);
            ObjectNode customer = requireObject(root.get("customer"), "customer", CUSTOMER_FIELDS);
            ObjectNode installation = requireObject(
                    root.get("installation"), "installation", INSTALLATION_FIELDS);
            ActivationManifestPayload payload = new ActivationManifestPayload(
                    text(root, "schema"),
                    DomainIdentifier.parse(text(root, "activation_id")),
                    new CustomerIdentity(text(customer, "customer_id"), text(customer, "legal_name")),
                    new ManifestInstallation(
                            DomainIdentifier.parse(text(installation, "installation_id")),
                            text(installation, "fingerprint_version"),
                            text(installation, "fingerprint")),
                    InstallationProfile.parse(text(root, "profile")),
                    AllocationTier.valueOf(text(root, "allocation_tier").toUpperCase(java.util.Locale.ROOT)),
                    text(root, "catalog_version"),
                    integer(root, "host_limit"),
                    stringSet(root.get("capabilities"), "capabilities"),
                    longMap(root.get("quotas"), "quotas"),
                    Instant.parse(text(root, "valid_from")),
                    Instant.parse(text(root, "valid_until")),
                    Math.toIntExact(integer(root, "grace_period_days")),
                    Instant.parse(text(root, "issued_at")),
                    text(root, "issuer"),
                    integer(root, "sequence"),
                    text(root, "key_id"));
            return new ActivationManifest(payload, text(root, "signature"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("activation manifest is invalid", error);
        } catch (Exception error) {
            throw new IllegalArgumentException("activation manifest cannot be decoded", error);
        }
    }

    private static ObjectNode requireObject(JsonNode node, String name, Set<String> expectedFields) {
        if (!(node instanceof ObjectNode object) || object.size() != expectedFields.size()) {
            throw new IllegalArgumentException(name + " must contain the exact contract fields");
        }
        Set<String> actual = new HashSet<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            actual.add(property.getKey());
        }
        if (!actual.equals(expectedFields)) {
            throw new IllegalArgumentException(name + " contains unknown or missing fields");
        }
        return object;
    }

    private static String text(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isString()) {
            throw new IllegalArgumentException(field + " must be a JSON string");
        }
        return node.asString();
    }

    private static long integer(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return node.asLong();
    }

    private static Set<String> stringSet(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isString() || !values.add(value.asString())) {
                throw new IllegalArgumentException(field + " must contain unique strings");
            }
        }
        return Set.copyOf(values);
    }

    private static Map<String, Long> longMap(JsonNode node, String field) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Long> values = new HashMap<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!property.getValue().isIntegralNumber() || property.getValue().asLong() < 0) {
                throw new IllegalArgumentException(field + " values must be non-negative integers");
            }
            values.put(property.getKey(), property.getValue().asLong());
        }
        return Map.copyOf(values);
    }
}
