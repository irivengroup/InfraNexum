package io.infranexum.adapters.jiraassets;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

/** JDK HTTP transport with HTTPS-only host pinning, redirect refusal, request timeout and bounded bodies. */
public final class JdkJiraAssetsTransport implements JiraAssetsTransport {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 2_097_152;

    @FunctionalInterface
    interface Sender {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Sender sender;
    private final int maximumResponseBytes;

    public JdkJiraAssetsTransport(HttpClient client, int maximumResponseBytes) {
        Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Jira Assets HttpClient must refuse redirects");
        }
        this.sender = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        this.maximumResponseBytes = validateMaximumResponseBytes(maximumResponseBytes);
    }

    JdkJiraAssetsTransport(Sender sender, int maximumResponseBytes) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.maximumResponseBytes = validateMaximumResponseBytes(maximumResponseBytes);
    }

    @Override
    public Response execute(Request request) {
        Objects.requireNonNull(request, "request");
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        byte[] body = request.body();
        if (request.method().equals("GET")) builder.GET();
        else builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<InputStream> response = sender.send(builder.build());
            try (InputStream stream = Objects.requireNonNull(response.body(), "response body")) {
                byte[] bounded = stream.readNBytes(maximumResponseBytes + 1);
                if (bounded.length > maximumResponseBytes) {
                    throw new JiraAssetsProtocolException("Jira Assets response exceeds configured maximum size");
                }
                return new Response(response.statusCode(), response.headers().map(), bounded);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JiraAssetsUnavailableException("Jira Assets request was interrupted", interrupted);
        } catch (IOException failure) {
            throw new JiraAssetsUnavailableException("Jira Assets transport failed", failure);
        }
    }

    private static int validateMaximumResponseBytes(int value) {
        if (value < 1 || value > 8_388_608) {
            throw new IllegalArgumentException("maximumResponseBytes must be between 1 and 8388608");
        }
        return value;
    }
}
