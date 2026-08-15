package io.infranexum.identity.access.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable version of an access policy and its controlled lifecycle state. */
public record AccessPolicy(
        DomainIdentifier id,
        DomainIdentifier organizationId,
        String code,
        long version,
        DomainIdentifier ownerId,
        String purpose,
        int priority,
        AuthorizationScope scope,
        PolicyState state,
        Instant effectiveFrom,
        DomainIdentifier approvedBy,
        Instant approvedAt,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant retiredAt,
        Instant createdAt,
        Instant updatedAt,
        List<PolicyRule> rules) {
    public static final DomainIdentifier SYSTEM_OWNER_ID = DomainIdentifier.parse("00000000-0000-7000-8000-000000000001");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{1,31}(?:\\.[a-z][a-z0-9_-]{1,31})+");

    public AccessPolicy {
        Objects.requireNonNull(id, "id");
        code = normalizeCode(code);
        if (version < 1) throw new IllegalArgumentException("policy version must be positive");
        Objects.requireNonNull(ownerId, "ownerId");
        purpose = PolicyCondition.bounded(purpose, "purpose", 500);
        if (priority < 0 || priority > 10_000) throw new IllegalArgumentException("policy priority must be between 0 and 10000");
        Objects.requireNonNull(scope, "scope");
        if (!Objects.equals(organizationId, scope.organizationId())) throw new IllegalArgumentException("policy organization and scope must agree");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt) || effectiveFrom.isBefore(createdAt)) throw new IllegalArgumentException("policy timestamps are inconsistent");
        if ((approvedBy == null) != (approvedAt == null)) throw new IllegalArgumentException("policy approval actor and timestamp must be paired");
        if (state.ordinal() >= PolicyState.APPROVED.ordinal() && approvedBy == null) throw new IllegalArgumentException("approved policy state requires approval evidence");
        if ((state == PolicyState.ACTIVE || state == PolicyState.DEPRECATED || state == PolicyState.RETIRED) && activatedAt == null) throw new IllegalArgumentException("activated policy state requires activation timestamp");
        if ((state == PolicyState.DEPRECATED || state == PolicyState.RETIRED) && deprecatedAt == null) throw new IllegalArgumentException("deprecated policy state requires deprecation timestamp");
        if (state == PolicyState.RETIRED && retiredAt == null) throw new IllegalArgumentException("retired policy state requires retirement timestamp");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.isEmpty() || rules.size() > 256) throw new IllegalArgumentException("policy requires between 1 and 256 rules");
        long uniquePositions = rules.stream().map(PolicyRule::position).distinct().count();
        if (uniquePositions != rules.size()) throw new IllegalArgumentException("policy rule positions must be unique");
    }

    public boolean systemPolicy() { return code.startsWith("system."); }

    public AccessPolicy validatePolicy(Instant now) {
        requireUserManaged();
        requireState(PolicyState.DRAFT, "only draft policies can be validated");
        return copy(PolicyState.VALIDATED, approvedBy, approvedAt, activatedAt, deprecatedAt, retiredAt, now);
    }

    public AccessPolicy approve(DomainIdentifier actorId, Instant now) {
        requireUserManaged();
        requireState(PolicyState.VALIDATED, "only validated policies can be approved");
        Objects.requireNonNull(actorId, "actorId");
        if (ownerId.equals(actorId)) throw new IdentityAccessException("IAM_POLICY_SELF_APPROVAL_FORBIDDEN", "policy owner cannot approve the same policy version");
        return copy(PolicyState.APPROVED, actorId, now, null, null, null, now);
    }

    public AccessPolicy activate(Instant now) {
        requireUserManaged();
        if (state != PolicyState.APPROVED && state != PolicyState.DEPRECATED) {
            throw new IdentityAccessException("IAM_POLICY_STATE_INVALID", "only approved or deprecated policies can be activated");
        }
        return copy(PolicyState.ACTIVE, approvedBy, approvedAt, now, null, null, now);
    }

    public AccessPolicy deprecate(Instant now) {
        requireUserManaged();
        requireState(PolicyState.ACTIVE, "only active policies can be deprecated");
        return copy(PolicyState.DEPRECATED, approvedBy, approvedAt, activatedAt, now, null, now);
    }

    public AccessPolicy retire(Instant now) {
        requireUserManaged();
        requireState(PolicyState.DEPRECATED, "only deprecated policies can be retired");
        return copy(PolicyState.RETIRED, approvedBy, approvedAt, activatedAt, deprecatedAt, now, now);
    }

    public boolean effectiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return state == PolicyState.ACTIVE && !at.isBefore(effectiveFrom);
    }

    public static String normalizeCode(String value) {
        String normalized = PolicyCondition.bounded(value, "code", 128).toLowerCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("policy code is invalid");
        return normalized;
    }

    private void requireUserManaged() {
        if (systemPolicy()) throw new IdentityAccessException("IAM_SYSTEM_POLICY_PROTECTED", "system policy versions are immutable");
    }

    private void requireState(PolicyState expected, String message) {
        if (state != expected) throw new IdentityAccessException("IAM_POLICY_STATE_INVALID", message);
    }

    private AccessPolicy copy(PolicyState nextState, DomainIdentifier nextApprovedBy, Instant nextApprovedAt,
            Instant nextActivatedAt, Instant nextDeprecatedAt, Instant nextRetiredAt, Instant now) {
        Objects.requireNonNull(now, "now");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("policy transition time cannot move backwards");
        return new AccessPolicy(id, organizationId, code, version, ownerId, purpose, priority, scope, nextState,
                effectiveFrom, nextApprovedBy, nextApprovedAt, nextActivatedAt, nextDeprecatedAt, nextRetiredAt,
                createdAt, now, rules);
    }
}
