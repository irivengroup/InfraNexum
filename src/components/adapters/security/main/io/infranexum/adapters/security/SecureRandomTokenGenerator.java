package io.infranexum.adapters.security;

import io.infranexum.identity.local.ports.SecureTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.bouncycastle.crypto.digests.SHA256Digest;

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
        byte[] input = token.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = new byte[32];
        try {
            SHA256Digest sha256 = new SHA256Digest();
            sha256.update(input, 0, input.length);
            sha256.doFinal(digest, 0);
            return HexFormat.of().formatHex(digest);
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
            java.util.Arrays.fill(digest, (byte) 0);
        }
    }
}
