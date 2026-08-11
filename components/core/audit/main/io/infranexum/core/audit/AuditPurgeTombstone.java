package io.infranexum.core.audit;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable proof that a regulatory purge workflow occurred without deleting its trace. */
public record AuditPurgeTombstone(
        DomainIdentifier tombstoneId,
        AuditScope scope,
        String policyId,
        DomainIdentifier approvedByFirst,
        DomainIdentifier approvedBySecond,
        Instant purgedAt,
        String proofSha256,
        String reason) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AuditPurgeTombstone {
        Objects.requireNonNull(tombstoneId, "tombstoneId");
        Objects.requireNonNull(scope, "scope");
        policyId = text(policyId, "policyId", 160);
        Objects.requireNonNull(approvedByFirst, "approvedByFirst");
        Objects.requireNonNull(approvedBySecond, "approvedBySecond");
        if (approvedByFirst.equals(approvedBySecond)) throw new IllegalArgumentException("regulatory purge requires two distinct approvers");
        Objects.requireNonNull(purgedAt, "purgedAt");
        Objects.requireNonNull(proofSha256, "proofSha256");
        if (!SHA256.matcher(proofSha256).matches()) throw new IllegalArgumentException("invalid purge proof digest");
        reason = text(reason, "reason", 1024);
    }

    private static String text(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
