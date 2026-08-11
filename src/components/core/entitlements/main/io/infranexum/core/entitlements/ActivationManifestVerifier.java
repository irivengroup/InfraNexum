package io.infranexum.core.entitlements;

import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityDefinition;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaClass;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.SignatureException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Offline Ed25519 verifier and semantic validator for Pro/Enterprise rights. */
public final class ActivationManifestVerifier {
    public ActivationVerificationResult verify(ActivationManifest manifest, ActivationValidationContext context) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(context, "context");
        ActivationManifestPayload payload = manifest.payload();
        TrustedKey key = context.trustedKeyStore().find(payload.keyId())
                .orElseThrow(() -> invalid("unknown activation signing key"));
        if (!key.isValidAt(payload.issuedAt()) || context.revocations().isKeyRevoked(payload.keyId(), context.now())) {
            throw revoked("activation signing key is not trusted at the evaluation instant");
        }
        verifySignature(manifest, key);
        if (context.revocations().isActivationRevoked(payload.activationId(), context.now())) {
            throw revoked("activation manifest is revoked");
        }
        if (!payload.customer().customerId().equals(context.customerId())) {
            throw invalid("activation customer does not match the installation owner");
        }
        if (!payload.installation().matches(context.installationIdentity())) {
            throw invalid("activation installation binding does not match");
        }
        if (payload.profile() != context.installedProfile()) {
            throw invalid("activation profile does not match the installed profile");
        }
        if (!payload.catalogVersion().equals(context.catalogVersion())
                || !payload.catalogVersion().equals(context.capabilityCatalog().version())
                || !payload.catalogVersion().equals(context.quotaCatalog().version())) {
            throw invalid("activation catalogue version does not match the runtime catalogue");
        }
        if (!context.acceptedSequence().accepts(payload.sequence(), payload.activationId())) {
            throw invalid("activation sequence is lower than or conflicts with the accepted sequence");
        }
        QuotaAllocationPlan plan = validateQuotas(payload, context);
        validateCapabilities(payload, context);
        if (payload.hostLimit() != plan.limit("rsot.managed_hosts.max")) {
            throw invalid("host_limit must equal rsot.managed_hosts.max");
        }
        ActivationUsageState state;
        if (context.now().isBefore(payload.validFrom())) {
            throw invalid("activation is not valid yet");
        }
        java.time.Instant graceUntil = payload.validUntil().plus(payload.gracePeriodDays(), ChronoUnit.DAYS);
        if (context.now().isBefore(payload.validUntil())) {
            state = ActivationUsageState.ACTIVE;
        } else if (context.now().isBefore(graceUntil)) {
            state = ActivationUsageState.GRACE;
        } else {
            state = ActivationUsageState.HARD_STOPPED;
        }
        return new ActivationVerificationResult(state, payload, plan, payload.capabilities(), graceUntil);
    }

    private static QuotaAllocationPlan validateQuotas(
            ActivationManifestPayload payload, ActivationValidationContext context) {
        if (!payload.quotas().keySet().equals(context.quotaCatalog().keys())) {
            throw invalid("activation quota keys must exactly match the certified catalogue");
        }
        try {
            java.util.Map<String, Long> adjustable = payload.quotas().entrySet().stream()
                    .filter(entry -> context.quotaCatalog().require(entry.getKey()).quotaClass()
                            == QuotaClass.COMMERCIAL_SCALABLE)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
            QuotaAllocationPlan plan = context.quotaCatalog().allocate(
                    payload.profile(), payload.allocationTier(), payload.catalogVersion(), adjustable);
            if (!plan.limits().equals(payload.quotas())) {
                throw new IllegalArgumentException("fixed quota values differ from the certified profile");
            }
            return plan;
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw invalid("activation quota policy is invalid: " + error.getMessage());
        }
    }

    private static void validateCapabilities(
            ActivationManifestPayload payload, ActivationValidationContext context) {
        Set<String> unknown = new HashSet<>();
        for (String value : payload.capabilities()) {
            CapabilityCode code;
            try {
                code = new CapabilityCode(value);
            } catch (IllegalArgumentException error) {
                unknown.add(value);
                continue;
            }
            CapabilityDefinition definition = context.capabilityCatalog().find(code);
            if (definition == null || !definition.allowedProfiles().contains(payload.profile())) {
                unknown.add(value);
            }
        }
        if (!unknown.isEmpty()) {
            throw invalid("activation contains unknown or profile-incompatible capabilities");
        }
    }

    private static void verifySignature(ActivationManifest manifest, TrustedKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.publicKey());
            verifier.update(manifest.payload().canonicalBytes());
            if (!verifier.verify(manifest.signatureBytes())) {
                throw invalid("activation signature verification failed");
            }
        } catch (SignatureException error) {
            throw invalid("activation signature verification failed");
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Ed25519 is required by the Java platform", error);
        }
    }

    private static ActivationValidationException invalid(String message) {
        return new ActivationValidationException(EntitlementErrorCodes.ACTIVATION_INVALID, message);
    }

    private static ActivationValidationException revoked(String message) {
        return new ActivationValidationException(EntitlementErrorCodes.ACTIVATION_REVOKED, message);
    }
}
