package io.infranexum.identity.access.application;

import io.infranexum.core.audit.AuditEntry;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.audit.AuditScope;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.domain.AccessPolicy;
import io.infranexum.identity.access.domain.PolicyAttributeBag;
import io.infranexum.identity.access.domain.PolicyCondition;
import io.infranexum.identity.access.domain.PolicyDecision;
import io.infranexum.identity.access.domain.PolicyEffect;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyEvaluationResult;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.domain.PolicyOperator;
import io.infranexum.identity.access.domain.PolicyRule;
import io.infranexum.identity.access.ports.AccessPolicyRepository;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.PolicyDecisionObserver;
import io.infranexum.identity.access.ports.PolicyInformationPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Pure deny-overrides PDP with a bounded cache keyed by policy and attribute versions. */
public final class PolicyDecisionService {
    private static final Duration DECISION_TTL = Duration.ofSeconds(30);
    private static final int MAX_CACHE_ENTRIES = 4096;

    private final AccessPolicyRepository repository;
    private final PolicyInformationPort information;
    private final IdentityAccessFeaturePolicy features;
    private final PolicyDecisionObserver observer;
    private final AuditJournal audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final ConcurrentHashMap<CacheKey, CoreDecision> cache = new ConcurrentHashMap<>();
    private final AtomicReference<String> cachedPolicyVersion = new AtomicReference<>();

    public PolicyDecisionService(
            AccessPolicyRepository repository,
            PolicyInformationPort information,
            IdentityAccessFeaturePolicy features,
            PolicyDecisionObserver observer,
            AuditJournal audit,
            UuidV7Generator ids,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.information = Objects.requireNonNull(information, "information");
        this.features = Objects.requireNonNull(features, "features");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Evaluates one request and always emits a fresh decision identifier. */
    public PolicyEvaluationResult decide(PolicyEvaluationRequest request, DomainIdentifier correlationId, String origin) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(origin, "origin");
        Instant started = clock.instant();
        if (!features.supportsAdvancedAuthorization()) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.NOT_APPLICABLE, "IAM_ABAC_UNAVAILABLE_FOR_PROFILE", Set.of(),
                            List.of("advanced authorization is unavailable for the active profile"), "unavailable", List.of()));
        }
        if (!request.rbacPermitted()) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.DENY, "IAM_RBAC_BASELINE_DENIED", Set.of(),
                            List.of("advanced authorization cannot override a denied RBAC baseline"), "rbac-denied", List.of()));
        }

        List<AccessPolicy> policies;
        try {
            policies = repository.activePolicies(request.scope(), started).stream()
                    .sorted(Comparator.comparingInt(AccessPolicy::priority).reversed()
                            .thenComparing(AccessPolicy::code).thenComparingLong(AccessPolicy::version))
                    .toList();
        } catch (RuntimeException failure) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.INDETERMINATE, "IAM_POLICY_RETRIEVAL_UNAVAILABLE", Set.of(),
                            List.of("policy retrieval point is unavailable"), "unavailable", List.of()));
        }
        String policyVersion = policySetVersion(policies);
        invalidateOnVersionChange(policyVersion);
        if (request.requestedPolicyVersion() != null && !request.requestedPolicyVersion().equals(policyVersion)) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.INDETERMINATE, "IAM_POLICY_VERSION_MISMATCH", Set.of(),
                            List.of("requested policy version does not match the active policy set"), policyVersion, List.of()));
        }
        if (policies.isEmpty()) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.NOT_APPLICABLE, "IAM_POLICY_NOT_APPLICABLE", Set.of(),
                            List.of("no active policy applies to the requested scope"), policyVersion, List.of()));
        }

        PolicyAttributeBag attributes;
        try {
            attributes = information.resolve(request, started);
        } catch (RuntimeException failure) {
            return finish(request, correlationId, origin, started, false,
                    core(PolicyDecision.INDETERMINATE, "IAM_POLICY_INFORMATION_UNAVAILABLE", Set.of(),
                            List.of("required policy attributes are unavailable"), policyVersion, List.of()));
        }
        CacheKey key = CacheKey.of(request, policyVersion, attributes.fingerprintMaterial());
        CoreDecision cached = cache.get(key);
        if (cached != null) return finish(request, correlationId, origin, started, true, cached);

        CoreDecision evaluated = evaluatePolicies(request, attributes, policies, policyVersion);
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, evaluated);
        return finish(request, correlationId, origin, started, false, evaluated);
    }

    /** Current active-policy set fingerprint used by clients for deterministic simulations. */
    public String activePolicyVersion(io.infranexum.identity.access.domain.AuthorizationScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (!features.supportsAdvancedAuthorization()) return "unavailable";
        return policySetVersion(repository.activePolicies(scope, clock.instant()));
    }

    private CoreDecision evaluatePolicies(
            PolicyEvaluationRequest request,
            PolicyAttributeBag attributes,
            List<AccessPolicy> policies,
            String policyVersion) {
        boolean permit = false;
        boolean indeterminate = false;
        LinkedHashSet<PolicyObligation> obligations = new LinkedHashSet<>();
        List<String> advice = new ArrayList<>();
        List<String> matchedPolicies = new ArrayList<>();
        for (AccessPolicy policy : policies) {
            PolicyOutcome outcome = evaluatePolicy(request, attributes, policy);
            if (outcome.decision() == PolicyDecision.NOT_APPLICABLE) continue;
            matchedPolicies.add(policy.code() + "@" + policy.version());
            advice.addAll(outcome.advice());
            if (outcome.decision() == PolicyDecision.DENY) {
                return core(PolicyDecision.DENY, "IAM_POLICY_DENY_OVERRIDES", Set.of(), advice, policyVersion, matchedPolicies);
            }
            if (outcome.decision() == PolicyDecision.INDETERMINATE) indeterminate = true;
            if (outcome.decision() == PolicyDecision.PERMIT) {
                permit = true;
                obligations.addAll(outcome.obligations());
            }
        }
        if (indeterminate) {
            return core(PolicyDecision.INDETERMINATE, "IAM_POLICY_ATTRIBUTE_INDETERMINATE", Set.of(), advice, policyVersion, matchedPolicies);
        }
        if (permit) {
            return core(PolicyDecision.PERMIT, "IAM_POLICY_PERMIT", Set.copyOf(obligations), advice, policyVersion, matchedPolicies);
        }
        return core(PolicyDecision.NOT_APPLICABLE, "IAM_POLICY_NOT_APPLICABLE", Set.of(), advice, policyVersion, matchedPolicies);
    }

    private static PolicyOutcome evaluatePolicy(PolicyEvaluationRequest request, PolicyAttributeBag attributes, AccessPolicy policy) {
        boolean permit = false;
        boolean indeterminate = false;
        LinkedHashSet<PolicyObligation> obligations = new LinkedHashSet<>();
        List<String> advice = new ArrayList<>();
        for (PolicyRule rule : policy.rules().stream().sorted(Comparator.comparingInt(PolicyRule::position)).toList()) {
            if (!rule.targets(request.action(), request.resourceType())) continue;
            RuleMatch match = match(rule, attributes);
            if (match == RuleMatch.NOT_MATCHED) continue;
            if (match == RuleMatch.INDETERMINATE) {
                indeterminate = true;
                continue;
            }
            if (!rule.advice().isEmpty()) advice.add(rule.advice());
            if (rule.effect() == PolicyEffect.DENY) return new PolicyOutcome(PolicyDecision.DENY, Set.of(), advice);
            permit = true;
            obligations.addAll(rule.obligations());
        }
        if (indeterminate) return new PolicyOutcome(PolicyDecision.INDETERMINATE, Set.of(), advice);
        if (permit) return new PolicyOutcome(PolicyDecision.PERMIT, Set.copyOf(obligations), advice);
        return new PolicyOutcome(PolicyDecision.NOT_APPLICABLE, Set.of(), advice);
    }

    private static RuleMatch match(PolicyRule rule, PolicyAttributeBag attributes) {
        for (PolicyCondition condition : rule.conditions()) {
            Set<String> actual = attributes.values(condition.source(), condition.attribute());
            boolean exists = !actual.isEmpty();
            if (!exists && condition.operator() != PolicyOperator.EXISTS) return RuleMatch.INDETERMINATE;
            boolean matched = switch (condition.operator()) {
                case EQUALS -> actual.size() == 1 && actual.contains(condition.expectedValue());
                case NOT_EQUALS -> actual.stream().noneMatch(condition.expectedValue()::equals);
                case CONTAINS -> actual.contains(condition.expectedValue());
                case EXISTS -> exists == Boolean.parseBoolean(condition.expectedValue());
            };
            if (!matched) return RuleMatch.NOT_MATCHED;
        }
        return RuleMatch.MATCHED;
    }

    private PolicyEvaluationResult finish(
            PolicyEvaluationRequest request,
            DomainIdentifier correlationId,
            String origin,
            Instant started,
            boolean cacheHit,
            CoreDecision core) {
        Instant finished = clock.instant();
        PolicyEvaluationResult result = new PolicyEvaluationResult(core.decision(), core.reasonCode(), core.obligations(),
                core.advice(), core.policyVersion(), ids.next(), finished.plus(DECISION_TTL), core.matchedPolicies());
        auditDecision(request, correlationId, origin, result, finished, cacheHit);
        observer.record(result, Duration.between(started, finished), cacheHit);
        return result;
    }

    private void auditDecision(PolicyEvaluationRequest request, DomainIdentifier correlationId, String origin,
            PolicyEvaluationResult result, Instant at, boolean cacheHit) {
        AuditScope scope = request.scope().organizationId() == null
                ? AuditScope.platform() : AuditScope.organization(request.scope().organizationId().toString());
        audit.append(new AuditEntry(ids.next(), scope, request.subjectId().toString(), "USER", "iam.authorization.decide",
                request.resourceType(), safeTarget(request.resourceId()), result.permitted() ? "ALLOW" : "DENY", at,
                correlationId, result.permitted() ? "SUCCESS" : "DENIED", origin, result.reasonCode(), null, null,
                Map.of("decision", result.decision().name(), "policy_version", result.policyVersion(),
                        "matched_policy_count", Integer.toString(result.matchedPolicies().size()),
                        "cache_hit", Boolean.toString(cacheHit)), "ELEVATED"));
    }

    private static String safeTarget(String value) {
        if (value.length() <= 200 && value.matches("[A-Za-z0-9._:-]+")) return value;
        return "policy-resource";
    }

    private void invalidateOnVersionChange(String policyVersion) {
        String previous = cachedPolicyVersion.getAndSet(policyVersion);
        if (previous != null && !previous.equals(policyVersion)) cache.clear();
    }

    private static String policySetVersion(List<AccessPolicy> policies) {
        StringBuilder material = new StringBuilder();
        policies.stream().sorted(Comparator.comparing(AccessPolicy::code).thenComparingLong(AccessPolicy::version))
                .forEach(policy -> material.append(policy.id()).append('|').append(policy.code()).append('|')
                        .append(policy.version()).append('|').append(policy.updatedAt()).append(';'));
        return "sha256:" + HexFormat.of().formatHex(sha256(material.toString()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static CoreDecision core(PolicyDecision decision, String reasonCode, Set<PolicyObligation> obligations,
            List<String> advice, String policyVersion, List<String> matchedPolicies) {
        return new CoreDecision(decision, reasonCode, Set.copyOf(obligations), List.copyOf(advice), policyVersion,
                List.copyOf(matchedPolicies));
    }

    private enum RuleMatch { MATCHED, NOT_MATCHED, INDETERMINATE }
    private record PolicyOutcome(PolicyDecision decision, Set<PolicyObligation> obligations, List<String> advice) {}
    private record CoreDecision(PolicyDecision decision, String reasonCode, Set<PolicyObligation> obligations,
            List<String> advice, String policyVersion, List<String> matchedPolicies) {}

    private record CacheKey(String subjectId, String action, String resourceType, String resourceId, String scope,
            String environment, String authentication, String capability, boolean rbac, String policyVersion,
            String attributes) {
        static CacheKey of(PolicyEvaluationRequest request, String policyVersion, String attributeMaterial) {
            return new CacheKey(request.subjectId().toString(), request.action(), request.resourceType(), request.resourceId(),
                    request.scope().toString(), new TreeMap<>(request.environment()).toString(), request.authenticationContext(),
                    request.capabilityVersion(), request.rbacPermitted(), policyVersion, attributeMaterial);
        }
    }
}
