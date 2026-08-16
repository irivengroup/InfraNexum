package io.infranexum.integrations;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 verifier using timestamp-bound canonical bytes and constant-time comparison. */
public final class HmacSha256WebhookAuthenticator {
    private static final String PREFIX = "sha256=";
    private final ConnectorSecretProvider secrets;
    private final Clock clock;

    public HmacSha256WebhookAuthenticator(ConnectorSecretProvider secrets, Clock clock) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void verify(ConnectorWebhookEndpoint endpoint, long epochSecond, String signature, byte[] payload) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(payload, "payload");
        Instant signedAt;
        try { signedAt = Instant.ofEpochSecond(epochSecond); }
        catch (RuntimeException invalid) { throw new WebhookAuthenticationException("invalid webhook timestamp"); }
        Duration drift = Duration.between(signedAt, clock.instant()).abs();
        if (drift.compareTo(endpoint.maximumClockSkew()) > 0) {
            throw new WebhookAuthenticationException("webhook timestamp is outside the allowed clock skew");
        }
        if (!signature.startsWith(PREFIX) || signature.length() != PREFIX.length() + 64) {
            throw new WebhookAuthenticationException("invalid webhook signature format");
        }
        byte[] supplied;
        try { supplied = HexFormat.of().parseHex(signature.substring(PREFIX.length())); }
        catch (IllegalArgumentException malformed) { throw new WebhookAuthenticationException("invalid webhook signature format"); }
        byte[] secret = secrets.resolve(endpoint.secretReference());
        if (secret == null || secret.length < 32) {
            throw new WebhookAuthenticationException("webhook secret is unavailable or too short");
        }
        byte[] expected;
        try { expected = sign(secret, epochSecond, payload); }
        finally { Arrays.fill(secret, (byte) 0); }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new WebhookAuthenticationException("webhook signature mismatch");
        }
    }

    /** Produces the protocol signature used by tests and certified connector implementations. */
    public static String signature(byte[] secret, long epochSecond, byte[] payload) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(payload, "payload");
        if (secret.length < 32) throw new IllegalArgumentException("webhook secret must contain at least 32 bytes");
        return PREFIX + HexFormat.of().formatHex(sign(secret, epochSecond, payload));
    }

    private static byte[] sign(byte[] secret, long epochSecond, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(epochSecond).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(payload);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
