package io.infranexum.core.capabilities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Authoritative evaluator combining installed composition, dependencies and entitlements. */
public final class CapabilityRegistry {
    private final CapabilityCatalog catalog;
    private final Clock clock;

    public CapabilityRegistry(CapabilityCatalog catalog, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CapabilitySnapshot evaluate(CapabilityEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        if (!catalog.version().equals(environment.catalogVersion())) {
            throw new IllegalArgumentException("capability catalogue version mismatch");
        }
        Instant evaluatedAt = clock.instant();
        Map<CapabilityCode, CapabilityDecision> decisions = new LinkedHashMap<>();
        for (CapabilityCode code : catalog.codes().stream().sorted().toList()) {
            DecisionBasis basis = decide(catalog.find(code), environment);
            String decisionHash = hash(canonicalDecision(code, basis, environment));
            decisions.put(code, new CapabilityDecision(
                    code,
                    basis.available(),
                    basis.reason(),
                    environment.profile(),
                    environment.topology(),
                    environment.roles(),
                    environment.traits(),
                    environment.dependencyFor(code),
                    environment.activationState(),
                    environment.catalogVersion(),
                    decisionHash,
                    evaluatedAt,
                    environment.profileVersion()));
        }
        String snapshotHash = hash(decisions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().capabilityHash())
                .collect(Collectors.joining("\n")));
        return new CapabilitySnapshot(
                environment.catalogVersion(), environment.profileVersion(), snapshotHash, evaluatedAt, decisions);
    }

    public CapabilityDecision evaluate(CapabilityCode code, CapabilityEnvironment environment) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(environment, "environment");
        if (!catalog.version().equals(environment.catalogVersion())) {
            throw new IllegalArgumentException("capability catalogue version mismatch");
        }
        CapabilityDefinition definition = catalog.find(code);
        Instant evaluatedAt = clock.instant();
        DecisionBasis basis = definition == null
                ? new DecisionBasis(false, CapabilityReasonCode.CAPABILITY_UNKNOWN)
                : decide(definition, environment);
        String decisionHash = hash(canonicalDecision(code, basis, environment));
        return new CapabilityDecision(
                code,
                basis.available(),
                basis.reason(),
                environment.profile(),
                environment.topology(),
                environment.roles(),
                environment.traits(),
                environment.dependencyFor(code),
                environment.activationState(),
                environment.catalogVersion(),
                decisionHash,
                evaluatedAt,
                environment.profileVersion());
    }

    private static DecisionBasis decide(CapabilityDefinition definition, CapabilityEnvironment environment) {
        if (!definition.allowedProfiles().contains(environment.profile())) {
            return new DecisionBasis(false, CapabilityReasonCode.PROFILE_CAPABILITY_NOT_INSTALLED);
        }
        if (!environment.installedCapabilities().contains(definition.code())) {
            return new DecisionBasis(false, CapabilityReasonCode.PROFILE_CAPABILITY_NOT_INSTALLED);
        }
        if (!environment.roles().containsAll(definition.requiredRoles())) {
            return new DecisionBasis(false, CapabilityReasonCode.ROLE_NOT_DEPLOYED);
        }
        if (!definition.allowedTopologies().contains(environment.topology())) {
            return new DecisionBasis(false, CapabilityReasonCode.TOPOLOGY_UNSUPPORTED);
        }
        if (!environment.traits().containsAll(definition.requiredTraits())) {
            return new DecisionBasis(false, CapabilityReasonCode.TRAIT_REQUIRED);
        }
        if (!environment.dependencyFor(definition.code()).isUsable()) {
            return new DecisionBasis(false, CapabilityReasonCode.DEPENDENCY_UNAVAILABLE);
        }
        if (definition.activationProtected() && !environment.activationState().permitsProtectedCapabilities()) {
            return new DecisionBasis(false, CapabilityReasonCode.ACTIVATION_REQUIRED);
        }
        if (definition.activationProtected()
                && environment.profile() != InstallationProfile.LITE
                && !environment.entitledCapabilities().contains(definition.code())) {
            return new DecisionBasis(false, CapabilityReasonCode.ENTITLEMENT_NOT_GRANTED);
        }
        return new DecisionBasis(true, CapabilityReasonCode.AVAILABLE);
    }

    private static String canonicalDecision(
            CapabilityCode code, DecisionBasis basis, CapabilityEnvironment environment) {
        return String.join("|",
                code.value(),
                Boolean.toString(basis.available()),
                basis.reason().name(),
                environment.profile().name(),
                environment.topology().code(),
                sorted(environment.roles().stream().map(Enum::name).collect(Collectors.toSet())),
                sorted(environment.traits().stream().map(TechnicalTrait::code).collect(Collectors.toSet())),
                environment.dependencyFor(code).name(),
                environment.activationState().name(),
                environment.catalogVersion(),
                Long.toString(environment.profileVersion()));
    }

    private static String sorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", error);
        }
    }

    private record DecisionBasis(boolean available, CapabilityReasonCode reason) {}
}
