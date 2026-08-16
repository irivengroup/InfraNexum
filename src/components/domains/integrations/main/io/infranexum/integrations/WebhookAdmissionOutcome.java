package io.infranexum.integrations;

/** Result of idempotent durable webhook admission. */
public record WebhookAdmissionOutcome(ConnectorDelivery delivery, boolean duplicate) {
    public WebhookAdmissionOutcome { if (delivery == null) throw new NullPointerException("delivery"); }
}
