package io.infranexum.adapters.jiraassets;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Narrow transport seam used to test Jira Assets protocol behavior without network access. */
@FunctionalInterface
public interface JiraAssetsTransport {
    Response execute(Request request);

    /** Bounded outbound request. Authorization values are never exposed by connector responses. */
    record Request(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout) {
        public Request {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(method, "method");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
            Objects.requireNonNull(timeout, "timeout");
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || !"api.atlassian.com".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Jira Assets transport is restricted to https://api.atlassian.com");
            }
            if (!(method.equals("GET") || method.equals("POST") || method.equals("PUT"))) throw new IllegalArgumentException("unsupported Jira Assets HTTP method");
        }
        @Override public byte[] body() { return Arrays.copyOf(body, body.length); }
    }

    /** Fully buffered but strictly bounded HTTP response returned by the transport. */
    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public Response {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("invalid HTTP status code");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
        }
        @Override public byte[] body() { return Arrays.copyOf(body, body.length); }
    }
}
