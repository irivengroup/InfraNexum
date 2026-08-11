package io.infranexum.core.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic signed snapshot manifest for an audit export. */
public record AuditExportManifest(
        String formatVersion,
        String keyId,
        AuditScope scope,
        long firstSequence,
        long lastSequence,
        long entryCount,
        Instant snapshotAt,
        String payloadSha256,
        String previousHash,
        String headHash) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AuditExportManifest {
        formatVersion = requireText(formatVersion, "formatVersion", 32);
        keyId = requireText(keyId, "keyId", 160);
        Objects.requireNonNull(scope, "scope");
        if (firstSequence < 1 || lastSequence < firstSequence || entryCount != lastSequence - firstSequence + 1) {
            throw new IllegalArgumentException("invalid export sequence interval");
        }
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        payloadSha256 = digest(payloadSha256, "payloadSha256");
        previousHash = digest(previousHash, "previousHash");
        headHash = digest(headHash, "headHash");
    }

    /** Canonical properties representation signed by Ed25519. */
    public String canonicalText() {
        return "entryCount=" + entryCount + "\n"
                + "firstSequence=" + firstSequence + "\n"
                + "formatVersion=" + formatVersion + "\n"
                + "headHash=" + headHash + "\n"
                + "keyId=" + keyId + "\n"
                + "lastSequence=" + lastSequence + "\n"
                + "payloadSha256=" + payloadSha256 + "\n"
                + "previousHash=" + previousHash + "\n"
                + "scopeId=" + scope.id() + "\n"
                + "scopeType=" + scope.type() + "\n"
                + "snapshotAt=" + snapshotAt + "\n";
    }

    private static String requireText(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl) || value.contains("=")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    private static String digest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA256.matcher(value).matches()) throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
