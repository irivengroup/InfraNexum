package io.infranexum.integrations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable local record prepared for an outbound provider synchronization.
 *
 * <p>Values cross a mutating provider boundary, so they are validated without trimming or other
 * silent normalization. A caller must explicitly correct malformed local data before it can leave
 * InfraNexum.</p>
 */
public record ConnectorOutboundRecord(String sourceIdentity, Map<String, String> fields, boolean deleted) {
    private static final int MAX_IDENTITY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 4_096;

    public ConnectorOutboundRecord {
        sourceIdentity = strictText(sourceIdentity, "sourceIdentity", 1, MAX_IDENTITY_LENGTH);
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(fields, "fields").entrySet()) {
            String field = new ConnectorFieldAuthority(entry.getKey(), ConnectorDataAuthority.MANUAL).field();
            String value = strictText(entry.getValue(), "field value", 1, MAX_VALUE_LENGTH);
            if (normalized.putIfAbsent(field, value) != null) {
                throw new IllegalArgumentException("duplicate outbound field: " + field);
            }
        }
        if (normalized.size() > 512) {
            throw new IllegalArgumentException("outbound record supports at most 512 fields");
        }
        fields = Map.copyOf(normalized);
    }

    private static String strictText(String value, String field, int minimum, int maximum) {
        Objects.requireNonNull(value, field);
        if (!value.equals(value.strip()) || value.length() < minimum || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
