package io.infranexum.server.http.idempotency;

import io.infranexum.server.http.AuthenticatedActorContext;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.IdempotencyLedger;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Fail-closed HTTP idempotency boundary for mutations not already protected inside a bounded-context transaction.
 *
 * <p>A unique durable reservation is acquired before controller execution. Completed 2xx/3xx responses are replayed
 * byte-for-byte. A process interruption can leave an IN_PROGRESS record; it is deliberately not expired automatically,
 * because the business transaction may already have committed and re-execution could duplicate the mutation.
 */
public final class ApiIdempotencyFilter extends OncePerRequestFilter implements Ordered {
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "X-Idempotent-Replay";
    private static final int MIN_KEY = 8;
    private static final int MAX_KEY = 200;

    private final ApiIdempotencyPolicy policy;
    private final IdempotencyLedger ledger;
    private final ApiProblemSupport problems;
    private final Clock clock;

    public ApiIdempotencyFilter(ApiIdempotencyPolicy policy, IdempotencyLedger ledger, ApiProblemSupport problems, @Qualifier("platformClock") Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 50; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return policy.operation(request).isEmpty(); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String operation = policy.operation(request).orElseThrow();
        String key = normalizeKey(request.getHeader(HEADER));
        if (key == null) { reject(response, request, HttpStatus.BAD_REQUEST, "INFRANEXUM_IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required", "Idempotency-Key must contain 8..200 safe characters"); return; }

        Object actorValue = request.getAttribute(AuthenticatedActorContext.ACCOUNT_ATTRIBUTE);
        if (!(actorValue instanceof DomainIdentifier actor)) { reject(response, request, HttpStatus.UNAUTHORIZED, "INFRANEXUM_IDEMPOTENCY_ACTOR_REQUIRED", "Authenticated actor required", "Idempotency protection requires an authenticated actor"); return; }

        BufferedHttpServletRequest buffered;
        try { buffered = new BufferedHttpServletRequest(request); }
        catch (BufferedHttpServletRequest.RequestBodyTooLargeException tooLarge) { reject(response, request, HttpStatus.PAYLOAD_TOO_LARGE, "INFRANEXUM_IDEMPOTENCY_BODY_TOO_LARGE", "Request body too large", "Idempotent mutation bodies are limited to 1 MiB"); return; }

        String fingerprint = fingerprint(buffered);
        String scope = actor.toString();
        IdempotencyLedger.Entry existing = ledger.find(scope, operation, key).orElse(null);
        if (existing != null) { handleExisting(existing, fingerprint, response, request); return; }
        if (!ledger.reserve(scope, operation, key, fingerprint, clock.instant())) {
            IdempotencyLedger.Entry raced = ledger.find(scope, operation, key).orElse(null);
            if (raced == null) { reject(response, request, HttpStatus.CONFLICT, "INFRANEXUM_IDEMPOTENCY_RACE", "Idempotency reservation conflict", "The idempotency key was reserved concurrently; retry after the concurrent request completes"); return; }
            handleExisting(raced, fingerprint, response, request); return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(buffered, wrapped);
            int status = wrapped.getStatus();
            byte[] body = wrapped.getContentAsByteArray();
            if (status >= 200 && status < 400) {
                ledger.complete(scope, operation, key, fingerprint, status, wrapped.getContentType(), wrapped.getHeader("ETag"), wrapped.getHeader("Location"), Base64.getEncoder().encodeToString(body), clock.instant());
            } else if (status >= 500) {
                ledger.markIndeterminate(scope, operation, key, fingerprint, clock.instant());
            } else {
                ledger.release(scope, operation, key, fingerprint);
            }
            wrapped.copyBodyToResponse();
        } catch (IOException | ServletException | RuntimeException failure) {
            ledger.markIndeterminate(scope, operation, key, fingerprint, clock.instant());
            throw failure;
        }
    }

    private void handleExisting(IdempotencyLedger.Entry entry, String fingerprint, HttpServletResponse response, HttpServletRequest request) throws IOException {
        if (!entry.requestSha256().equals(fingerprint)) { reject(response, request, HttpStatus.CONFLICT, "INFRANEXUM_IDEMPOTENCY_CONFLICT", "Idempotency key conflict", "The same Idempotency-Key was already used with different request semantics"); return; }
        if (entry.state() != IdempotencyLedger.State.COMPLETED) { reject(response, request, HttpStatus.CONFLICT, "INFRANEXUM_IDEMPOTENCY_INDETERMINATE", "Idempotency result unavailable", "The original mutation is still in progress or its completion is indeterminate; automatic re-execution is blocked"); return; }
        response.resetBuffer();
        response.setStatus(entry.httpStatus());
        if (entry.contentType() != null) response.setContentType(entry.contentType());
        if (entry.etag() != null) response.setHeader("ETag", entry.etag());
        if (entry.location() != null) response.setHeader("Location", entry.location());
        response.setHeader(REPLAY_HEADER, "true");
        byte[] body = entry.responseBodyBase64() == null ? new byte[0] : Base64.getDecoder().decode(entry.responseBodyBase64());
        response.setContentLength(body.length);
        if (body.length > 0) response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private String fingerprint(BufferedHttpServletRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.getMethod().toUpperCase(java.util.Locale.ROOT));
            update(digest, request.getRequestURI());
            update(digest, request.getQueryString() == null ? "" : request.getQueryString());
            digest.update(request.body());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }

    private static String normalizeKey(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.length() < MIN_KEY || normalized.length() > MAX_KEY || !normalized.matches("[A-Za-z0-9._:-]+")) return null;
        return normalized;
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code, String title, String detail) throws IOException {
        problems.write(response, problems.problem(status, code, title, detail, Map.of(), Map.of(), request));
    }
}
