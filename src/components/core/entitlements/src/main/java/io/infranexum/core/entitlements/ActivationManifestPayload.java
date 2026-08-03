package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Signed fields of {@code infranexum.activation-manifest/v2}. */
public record ActivationManifestPayload(
        String schema,
        DomainIdentifier activationId,
        CustomerIdentity customer,
        ManifestInstallation installation,
        InstallationProfile profile,
        AllocationTier allocationTier,
        String catalogVersion,
        long hostLimit,
        Set<String> capabilities,
        Map<String, Long> quotas,
        Instant validFrom,
        Instant validUntil,
        int gracePeriodDays,
        Instant issuedAt,
        String issuer,
        long sequence,
        String keyId) {
    public static final String SCHEMA = "infranexum.activation-manifest/v2";

    public ActivationManifestPayload {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported activation manifest schema");
        }
        Objects.requireNonNull(activationId, "activationId");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(allocationTier, "allocationTier");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        if (profile == InstallationProfile.LITE) {
            throw new IllegalArgumentException("Lite activation manifests are forbidden");
        }
        validateTier(profile, allocationTier);
        if (hostLimit < 0) {
            throw new IllegalArgumentException("hostLimit must be non-negative");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("capabilities must contain non-blank values");
        }
        quotas = Map.copyOf(Objects.requireNonNull(quotas, "quotas"));
        if (quotas.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("quotas must contain named non-negative integer values");
        }
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(issuedAt, "issuedAt");
        InstallationIdentity.requireWholeSecond(validFrom, "validFrom");
        InstallationIdentity.requireWholeSecond(validUntil, "validUntil");
        InstallationIdentity.requireWholeSecond(issuedAt, "issuedAt");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (issuedAt.isAfter(validFrom)) {
            throw new IllegalArgumentException("issuedAt must not be after validFrom");
        }
        if (gracePeriodDays != 30) {
            throw new IllegalArgumentException("gracePeriodDays must equal 30");
        }
        issuer = requireText(issuer, "issuer");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        keyId = requireText(keyId, "keyId");
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("activation_id", activationId.toString());
        value.put("allocation_tier", allocationTier.name().toLowerCase(java.util.Locale.ROOT));
        value.put("capabilities", capabilities.stream().sorted().toList());
        value.put("catalog_version", catalogVersion);
        value.put("customer", Map.of("customer_id", customer.customerId(), "legal_name", customer.legalName()));
        value.put("grace_period_days", gracePeriodDays);
        value.put("host_limit", hostLimit);
        value.put("installation", Map.of(
                "fingerprint", installation.fingerprint(),
                "fingerprint_version", installation.fingerprintVersion(),
                "installation_id", installation.installationId().toString()));
        value.put("issued_at", issuedAt.toString());
        value.put("issuer", issuer);
        value.put("key_id", keyId);
        value.put("profile", profile.name().toLowerCase(java.util.Locale.ROOT));
        value.put("quotas", new TreeMap<>(quotas));
        value.put("schema", schema);
        value.put("sequence", sequence);
        value.put("valid_from", validFrom.toString());
        value.put("valid_until", validUntil.toString());
        return Map.copyOf(value);
    }

    public byte[] canonicalBytes() {
        return CanonicalJson.bytes(canonicalValue());
    }

    private static void validateTier(InstallationProfile profile, AllocationTier tier) {
        boolean valid = switch (profile) {
            case LITE -> false;
            case PRO -> tier == AllocationTier.STANDARD || tier == AllocationTier.ADVANCED;
            case ENTERPRISE -> tier == AllocationTier.STANDARD || tier == AllocationTier.ULTIMATE;
        };
        if (!valid) {
            throw new IllegalArgumentException("allocation tier is incompatible with activation profile");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
