package io.infranexum.adapters.outboundwebhook;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.OutboundNotificationDelivery;
import io.infranexum.integrations.OutboundNotificationEndpoint;
import io.infranexum.integrations.OutboundNotificationStatus;
import io.infranexum.integrations.OutboundNotificationTransportException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contract tests for signed HTTPS notification delivery and safe transport failure classification. */
final class JdkSignedWebhookTransportTest {
    private static final Instant NOW = Instant.parse("2026-08-17T16:00:00Z");
    private static final byte[] PAYLOAD = "{\"kind\":\"health\"}".getBytes(StandardCharsets.UTF_8);
    private static final DomainIdentifier DELIVERY_ID = new DomainIdentifier(
            UUID.fromString("01980000-0000-7001-8000-000000000001"));
    private static final OutboundNotificationEndpoint ENDPOINT = new OutboundNotificationEndpoint(
            new ConnectorKey("ops-webhook"), URI.create("https://events.example.test/infranexum"), "env:INX_TEST_SECRET",
            Duration.ofSeconds(7), true);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void sendsCanonicalHeadersPayloadAndZeroizesResolvedSecret() {
        StubHttpClient http = new StubHttpClient(204, Failure.NONE);
        byte[] resolved = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        var transport = new JdkSignedWebhookTransport(http, ignored -> resolved, Clock.fixed(NOW, ZoneOffset.UTC));

        transport.deliver(ENDPOINT, delivery());

        HttpRequest request = http.lastRequest;
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals(ENDPOINT.destination(), request.uri());
        assertEquals(ENDPOINT.requestTimeout(), request.timeout().orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("InfraNexum-Notification/2", request.headers().firstValue("User-Agent").orElseThrow());
        assertEquals(Long.toString(NOW.getEpochSecond()), request.headers().firstValue("X-InfraNexum-Timestamp").orElseThrow());
        assertEquals(DELIVERY_ID.toString(), request.headers().firstValue("X-InfraNexum-Delivery-ID").orElseThrow());
        assertEquals("infrastructure.health.changed", request.headers().firstValue("X-InfraNexum-Event").orElseThrow());
        assertEquals("sha256=d841a4e288d27f80be14f899366fead9ae7b56a1d4e6cac9b83233bfde62ccec",
                request.headers().firstValue("X-InfraNexum-Signature").orElseThrow());
        assertArrayEquals(PAYLOAD, body(request));
        assertArrayEquals(new byte[32], resolved, "resolved secrets must be zeroized after signing");
    }

    @Test
    void classifiesHttpStatusesAndNetworkFailuresWithoutLeakingBodies() {
        assertDoesNotThrow(() -> transport(200, Failure.NONE).deliver(ENDPOINT, delivery()));
        assertDoesNotThrow(() -> transport(299, Failure.NONE).deliver(ENDPOINT, delivery()));
        for (int status : List.of(408, 425, 429, 500, 503, 599)) {
            OutboundNotificationTransportException failure = assertThrows(OutboundNotificationTransportException.class,
                    () -> transport(status, Failure.NONE).deliver(ENDPOINT, delivery()));
            assertTrue(failure.retryable());
            assertEquals("NOTIFICATION_REMOTE_TRANSIENT", failure.code());
        }
        for (int status : List.of(300, 400, 401, 403, 404, 409, 499)) {
            OutboundNotificationTransportException failure = assertThrows(OutboundNotificationTransportException.class,
                    () -> transport(status, Failure.NONE).deliver(ENDPOINT, delivery()));
            assertFalse(failure.retryable());
            assertEquals("NOTIFICATION_REMOTE_REJECTED", failure.code());
        }
        OutboundNotificationTransportException io = assertThrows(OutboundNotificationTransportException.class,
                () -> transport(0, Failure.IO).deliver(ENDPOINT, delivery()));
        assertEquals("NOTIFICATION_NETWORK_FAILURE", io.code());
        assertTrue(io.retryable());

        OutboundNotificationTransportException interrupted = assertThrows(OutboundNotificationTransportException.class,
                () -> transport(0, Failure.INTERRUPTED).deliver(ENDPOINT, delivery()));
        assertEquals("NOTIFICATION_INTERRUPTED", interrupted.code());
        assertTrue(interrupted.retryable());
        assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
    }

    @Test
    void failsClosedOnWeakOrMissingSecretsAndRedirectCapableClients() {
        byte[] weak = new byte[31];
        OutboundNotificationTransportException weakFailure = assertThrows(OutboundNotificationTransportException.class,
                () -> new JdkSignedWebhookTransport(new StubHttpClient(204, Failure.NONE), ignored -> weak,
                        Clock.fixed(NOW, ZoneOffset.UTC)).deliver(ENDPOINT, delivery()));
        assertEquals("NOTIFICATION_SECRET_UNAVAILABLE", weakFailure.code());
        assertArrayEquals(new byte[31], weak);

        assertThrows(OutboundNotificationTransportException.class,
                () -> new JdkSignedWebhookTransport(new StubHttpClient(204, Failure.NONE), ignored -> null,
                        Clock.fixed(NOW, ZoneOffset.UTC)).deliver(ENDPOINT, delivery()));
        assertThrows(IllegalArgumentException.class,
                () -> new JdkSignedWebhookTransport(new RedirectingHttpClient(), ignored -> new byte[32],
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        assertThrows(NullPointerException.class, () -> new JdkSignedWebhookTransport(null, ignored -> new byte[32], Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new JdkSignedWebhookTransport(new StubHttpClient(204, Failure.NONE), null, Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new JdkSignedWebhookTransport(new StubHttpClient(204, Failure.NONE), ignored -> new byte[32], null));
    }

    @Test
    void signatureContractIsDeterministicAndValidatesInputs() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        assertEquals("sha256=d841a4e288d27f80be14f899366fead9ae7b56a1d4e6cac9b83233bfde62ccec",
                JdkSignedWebhookTransport.signature(secret, NOW.getEpochSecond(), DELIVERY_ID.toString(), PAYLOAD));
        assertThrows(IllegalArgumentException.class,
                () -> JdkSignedWebhookTransport.signature(new byte[31], 1L, DELIVERY_ID.toString(), PAYLOAD));
        assertThrows(NullPointerException.class, () -> JdkSignedWebhookTransport.signature(null, 1L, "id", PAYLOAD));
        assertThrows(NullPointerException.class, () -> JdkSignedWebhookTransport.signature(secret, 1L, null, PAYLOAD));
        assertThrows(NullPointerException.class, () -> JdkSignedWebhookTransport.signature(secret, 1L, "id", null));
    }

    private static JdkSignedWebhookTransport transport(int status, Failure failure) {
        return new JdkSignedWebhookTransport(new StubHttpClient(status, failure),
                ignored -> "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboundNotificationDelivery delivery() {
        return new OutboundNotificationDelivery(DELIVERY_ID, ENDPOINT.endpointKey(), "evt-20260817-1001",
                "infrastructure.health.changed", PAYLOAD, "a".repeat(64), OutboundNotificationStatus.PENDING, 0,
                NOW, NOW, null, null, null, null, 0, null);
    }

    private static byte[] body(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        var collector = new BodyCollector();
        publisher.subscribe(collector);
        return collector.result.join();
    }

    private enum Failure { NONE, IO, INTERRUPTED }

    private static class StubHttpClient extends HttpClient {
        private final int status;
        private final Failure failure;
        private HttpRequest lastRequest;
        StubHttpClient(int status, Failure failure) { this.status = status; this.failure = failure; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
            this.lastRequest = request;
            if (failure == Failure.IO) throw new IOException("simulated");
            if (failure == Failure.INTERRUPTED) throw new InterruptedException("simulated");
            return new StubResponse<>(request, status, null);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    private static final class RedirectingHttpClient extends StubHttpClient {
        RedirectingHttpClient() { super(204, Failure.NONE); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
    }

    private record StubResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(ByteBuffer item) { byte[] bytes = new byte[item.remaining()]; item.get(bytes); output.writeBytes(bytes); }
        @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
