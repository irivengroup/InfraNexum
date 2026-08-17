package io.infranexum.adapters.outboundwebhook;

import io.infranexum.integrations.ConnectorSecretProvider;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationTransport;
import io.infranexum.integrations.OutboundNotificationTransportException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HTTPS webhook transport with HMAC-SHA256 replay-bound signatures and no redirects. */
public final class JdkSignedWebhookTransport implements OutboundNotificationTransport {
    private static final String SIGNATURE_PREFIX = "sha256=";
    private final HttpClient http;
    private final ConnectorSecretProvider secrets;
    private final Clock clock;

    public JdkSignedWebhookTransport(HttpClient http, ConnectorSecretProvider secrets, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("notification HttpClient must refuse redirects");
        }
    }

    @Override
    public void deliver(OutboundNotificationEndpoint endpoint, OutboundNotificationDelivery delivery) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(delivery, "delivery");
        byte[] payload = delivery.payload();
        long timestamp = clock.instant().getEpochSecond();
        byte[] secret = secrets.resolve(endpoint.secretReference());
        if (secret == null || secret.length < 32) {
            if (secret != null) Arrays.fill(secret, (byte) 0);
            throw new OutboundNotificationTransportException("NOTIFICATION_SECRET_UNAVAILABLE", false);
        }
        String signature;
        try {
            signature = signature(secret, timestamp, delivery.deliveryId().toString(), payload);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint.destination())
                .timeout(endpoint.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "InfraNexum-Notification/2")
                .header("X-InfraNexum-Signature", signature)
                .header("X-InfraNexum-Timestamp", Long.toString(timestamp))
                .header("X-InfraNexum-Delivery-ID", delivery.deliveryId().toString())
                .header("X-InfraNexum-Event", delivery.eventType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return;
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            throw new OutboundNotificationTransportException(
                    retryable ? "NOTIFICATION_REMOTE_TRANSIENT" : "NOTIFICATION_REMOTE_REJECTED", retryable);
        } catch (IOException failure) {
            throw new OutboundNotificationTransportException("NOTIFICATION_NETWORK_FAILURE", true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OutboundNotificationTransportException("NOTIFICATION_INTERRUPTED", true);
        }
    }

    /** Produces the canonical signature for interoperability and contract tests. */
    public static String signature(byte[] secret, long epochSecond, String deliveryId, byte[] payload) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(payload, "payload");
        if (secret.length < 32) throw new IllegalArgumentException("notification secret must contain at least 32 bytes");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(epochSecond).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(deliveryId.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(payload);
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
