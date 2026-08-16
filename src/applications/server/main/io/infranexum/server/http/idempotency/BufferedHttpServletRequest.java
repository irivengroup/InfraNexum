package io.infranexum.server.http.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Re-readable request wrapper used to fingerprint a JSON mutation before controller execution. */
final class BufferedHttpServletRequest extends HttpServletRequestWrapper {
    static final int MAX_BODY_BYTES = 1_048_576;
    private final byte[] body;

    BufferedHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        byte[] candidate = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (candidate.length > MAX_BODY_BYTES) throw new RequestBodyTooLargeException();
        this.body = candidate;
    }

    byte[] body() { return body.clone(); }

    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { if (listener != null) { try { listener.onDataAvailable(); if (isFinished()) listener.onAllDataRead(); } catch (IOException e) { listener.onError(e); } } }
        };
    }

    @Override public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    static final class RequestBodyTooLargeException extends RuntimeException {}
}
