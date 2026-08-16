package io.infranexum.integrations;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated webhook content ready for durable admission. */
public record WebhookAdmission(DomainIdentifier deliveryId, ConnectorKey connectorKey, String externalDeliveryId, String payload, String payloadSha256, Instant receivedAt) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    public WebhookAdmission {
        Objects.requireNonNull(deliveryId, "deliveryId"); Objects.requireNonNull(connectorKey, "connectorKey"); Objects.requireNonNull(receivedAt, "receivedAt");
        externalDeliveryId = required(externalDeliveryId, "externalDeliveryId", 200);
        if (!DELIVERY_ID.matcher(externalDeliveryId).matches()) throw new IllegalArgumentException("invalid externalDeliveryId");
        payload = payload(payload);
        if (!SHA256.matcher(Objects.requireNonNull(payloadSha256, "payloadSha256")).matches()) throw new IllegalArgumentException("invalid payloadSha256");
    }
    private static String required(String value,String field,int maximum){Objects.requireNonNull(value,field);if(value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid "+field);String normalized=value.strip();if(normalized.isEmpty()||normalized.length()>maximum)throw new IllegalArgumentException("invalid "+field);return normalized;}
    private static String payload(String value){Objects.requireNonNull(value,"payload");if(value.isBlank()||value.length()>1_048_576)throw new IllegalArgumentException("invalid payload");return value;}
}
