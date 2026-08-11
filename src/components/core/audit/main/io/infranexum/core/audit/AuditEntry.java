package io.infranexum.core.audit;

import io.infranexum.core.contracts.DomainIdentifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Complete immutable audit event submitted by an authoritative domain. */
public record AuditEntry(
        DomainIdentifier auditId,
        AuditScope scope,
        String actorId,
        String actorType,
        String action,
        String targetType,
        String targetId,
        String authorizationDecision,
        Instant timestamp,
        DomainIdentifier correlationId,
        String result,
        String origin,
        String reason,
        String clientIp,
        String userAgent,
        Map<String, String> metadata,
        String sensitivity) {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,159}");
    private static final Pattern DECISION = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|authorization|cookie|private[_-]?key).*");
    private static final int MAX_METADATA_BYTES = 4 * 1024;

    public AuditEntry {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(scope, "scope");
        actorId = token(actorId, "actorId");
        actorType = token(actorType, "actorType");
        action = token(action, "action");
        targetType = token(targetType, "targetType");
        targetId = token(targetId, "targetId");
        authorizationDecision = decision(authorizationDecision, "authorizationDecision");
        Objects.requireNonNull(timestamp, "timestamp");
        result = decision(result, "result");
        origin = text(origin, "origin", 512, false);
        reason = text(reason, "reason", 1024, true);
        clientIp = text(clientIp, "clientIp", 64, true);
        userAgent = text(userAgent, "userAgent", 512, true);
        sensitivity = decision(sensitivity, "sensitivity");
        metadata = sanitizeMetadata(metadata);
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        String normalized = value.strip();
        if (!TOKEN.matcher(normalized).matches()) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }

    private static String decision(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!DECISION.matcher(normalized).matches()) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }

    private static String text(String value, String field, int maximum, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw new NullPointerException(field);
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            if (optional) return null;
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    private static Map<String, String> sanitizeMetadata(Map<String, String> supplied) {
        Objects.requireNonNull(supplied, "metadata");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> item : supplied.entrySet()) {
            String key = token(item.getKey(), "metadata key");
            if (SENSITIVE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("sensitive metadata key is forbidden: " + key);
            }
            String value = text(item.getValue(), "metadata value", 1024, false);
            sorted.put(key, value);
        }
        int bytes = sorted.entrySet().stream()
                .mapToInt(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + entry.getValue().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        if (bytes > MAX_METADATA_BYTES) throw new IllegalArgumentException("audit metadata exceeds 4096 bytes");
        return Map.copyOf(sorted);
    }
}
