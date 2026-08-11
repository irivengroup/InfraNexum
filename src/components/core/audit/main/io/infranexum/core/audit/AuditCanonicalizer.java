package io.infranexum.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Canonical byte representation used by audit integrity hashes and exports. */
public final class AuditCanonicalizer {
    public static final String GENESIS_HASH = "0".repeat(64);

    private AuditCanonicalizer() {}

    /** Computes the chain hash for one immutable record. */
    public static String hash(long sequence, String previousHash, AuditEntry entry) {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        String body = sequence + "\n" + previousHash + "\n" + canonicalEntry(entry);
        return sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns canonical JSON for an audit entry with stable field and metadata ordering. */
    public static String canonicalEntry(AuditEntry entry) {
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        field(out, "auditId", entry.auditId().toString());
        comma(out); field(out, "scopeType", entry.scope().type());
        comma(out); field(out, "scopeId", entry.scope().id());
        comma(out); field(out, "actorId", entry.actorId());
        comma(out); field(out, "actorType", entry.actorType());
        comma(out); field(out, "action", entry.action());
        comma(out); field(out, "targetType", entry.targetType());
        comma(out); field(out, "targetId", entry.targetId());
        comma(out); field(out, "authorizationDecision", entry.authorizationDecision());
        comma(out); field(out, "timestamp", entry.timestamp().toString());
        comma(out); nullableField(out, "correlationId", entry.correlationId() == null ? null : entry.correlationId().toString());
        comma(out); field(out, "result", entry.result());
        comma(out); field(out, "origin", entry.origin());
        comma(out); nullableField(out, "reason", entry.reason());
        comma(out); nullableField(out, "clientIp", entry.clientIp());
        comma(out); nullableField(out, "userAgent", entry.userAgent());
        comma(out); out.append("\"metadata\":{");
        boolean first = true;
        for (Map.Entry<String, String> item : new java.util.TreeMap<>(entry.metadata()).entrySet()) {
            if (!first) out.append(',');
            first = false;
            AuditJsonStrings.quote(out, item.getKey()); out.append(':'); AuditJsonStrings.quote(out, item.getValue());
        }
        out.append('}');
        comma(out); field(out, "sensitivity", entry.sensitivity());
        out.append('}');
        return out.toString();
    }

    /** Computes lowercase SHA-256. */
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void field(StringBuilder out, String name, String value) {
        AuditJsonStrings.quote(out, name); out.append(':'); AuditJsonStrings.quote(out, value);
    }

    private static void nullableField(StringBuilder out, String name, String value) {
        AuditJsonStrings.quote(out, name); out.append(':');
        if (value == null) out.append("null"); else AuditJsonStrings.quote(out, value);
    }

    private static void comma(StringBuilder out) { out.append(','); }

}
