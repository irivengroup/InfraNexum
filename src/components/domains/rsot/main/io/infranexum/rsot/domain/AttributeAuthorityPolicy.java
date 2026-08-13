package io.infranexum.rsot.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Versioned authority policy for one bounded object/attribute pattern. */
public record AttributeAuthorityPolicy(
        DomainIdentifier id,
        String objectType,
        String attributePath,
        AuthorityContext authorityContext,
        List<AuthorityContext> sourcePriority,
        Instant effectiveFrom,
        Instant effectiveUntil,
        String policyVersion,
        String approvalRef) {

    public AttributeAuthorityPolicy {
        Objects.requireNonNull(id, "id");
        objectType = boundedPattern(objectType, "objectType", 160);
        attributePath = boundedPattern(attributePath, "attributePath", 256);
        Objects.requireNonNull(authorityContext, "authorityContext");
        Objects.requireNonNull(sourcePriority, "sourcePriority");
        sourcePriority = List.copyOf(sourcePriority);
        if (sourcePriority.isEmpty() || new LinkedHashSet<>(sourcePriority).size() != sourcePriority.size()) {
            throw new IllegalArgumentException("sourcePriority must be non-empty and unique");
        }
        if (!sourcePriority.contains(authorityContext)) {
            throw new IllegalArgumentException("sourcePriority must contain the authority context");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
        policyVersion = token(policyVersion, "policyVersion", 64);
        approvalRef = token(approvalRef, "approvalRef", 200);
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveFrom) && (effectiveUntil == null || instant.isBefore(effectiveUntil));
    }

    public boolean matches(String candidateObjectType, String candidateAttributePath) {
        return match(objectType, normalize(candidateObjectType, "objectType", 160))
                && match(attributePath, normalize(candidateAttributePath, "attributePath", 256));
    }

    private static boolean match(String pattern, String value) {
        return pattern.endsWith(".*")
                ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    private static String boundedPattern(String value, String field, int max) {
        String normalized = normalize(value, field, max);
        long wildcards = normalized.chars().filter(character -> character == '*').count();
        if (wildcards > 1 || (wildcards == 1 && (!normalized.endsWith(".*") || normalized.length() <= 2))) {
            throw new IllegalArgumentException(field + " wildcard must be a single bounded terminal .* pattern");
        }
        if (normalized.equals("*") || normalized.equals(".*")) {
            throw new IllegalArgumentException(field + " cannot grant implicit global authority");
        }
        return normalized;
    }

    private static String normalize(String value, String field, int max) {
        return token(value, field, max).toLowerCase(Locale.ROOT);
    }

    private static String token(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
