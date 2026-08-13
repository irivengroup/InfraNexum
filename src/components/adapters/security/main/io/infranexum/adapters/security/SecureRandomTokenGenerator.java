package io.infranexum.adapters.security;

import io.infranexum.identity.local.ports.SecureTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Generates 256-bit opaque bearer/CSRF values and deterministic SHA-256 persistence fingerprints. */
public final class SecureRandomTokenGenerator implements SecureTokenGenerator {
    private final SecureRandom random;

    public SecureRandomTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String nextToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String sha256(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
