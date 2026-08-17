package io.infranexum.adapters.security;

import io.infranexum.identity.local.ports.PasswordHasher;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id implementation using the audited Bouncy Castle primitive and PHC-compatible encoding. */
public final class BouncyCastleArgon2idPasswordHasher implements PasswordHasher {
    public static final int MEMORY_KIB = 65536;
    public static final int ITERATIONS = 3;
    public static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_ACCEPTED_MEMORY_KIB = 262144;
    private static final int MAX_ACCEPTED_ITERATIONS = 10;
    private static final int MAX_ACCEPTED_PARALLELISM = 8;
    private static final String PREFIX = "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$";

    private final SecureRandom random;
    private final String dummyHash;

    public BouncyCastleArgon2idPasswordHasher(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
        char[] dummy = "InfraNexum-Dummy!Credential-2026".toCharArray();
        try {
            dummyHash = hash(dummy);
        } finally {
            Arrays.fill(dummy, '\0');
        }
    }

    @Override
    public String hash(char[] password) {
        Objects.requireNonNull(password, "password");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        try {
            return PREFIX + b64(salt) + "$" + b64(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    @Override
    public boolean verify(char[] password, String encodedHash) {
        Parsed parsed = parse(encodedHash);
        byte[] actual = derive(password, parsed.salt(), parsed.memoryKiB(), parsed.iterations(), parsed.parallelism());
        try {
            return MessageDigest.isEqual(actual, parsed.hash());
        } finally {
            Arrays.fill(actual, (byte) 0);
            parsed.clear();
        }
    }

    @Override
    public boolean needsRehash(String encodedHash) {
        Parsed parsed = parse(encodedHash);
        try {
            return parsed.memoryKiB() != MEMORY_KIB || parsed.iterations() != ITERATIONS || parsed.parallelism() != PARALLELISM;
        } finally {
            parsed.clear();
        }
    }

    @Override
    public void consumeEquivalentWork(char[] password) {
        verify(password, dummyHash);
    }

    private static byte[] derive(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
        byte[] input = utf8(password);
        byte[] result = new byte[HASH_BYTES];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(memoryKiB)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(input, result);
            return result;
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    private static byte[] utf8(char[] password) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[buffer.remaining()];
        // bytes is sized from remaining(), so get(bytes) cannot underflow. Avoid a finally block here:
        // javac duplicates its wipe loop into an exceptional path that is unreachable by construction.
        buffer.get(bytes);
        buffer.clear();
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }
        return bytes;
    }

    private static Parsed parse(String encodedHash) {
        Objects.requireNonNull(encodedHash, "encodedHash");
        String[] parts = encodedHash.split("\\$");
        if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
            throw new IllegalArgumentException("unsupported Argon2id encoding");
        }
        String[] params = parts[3].split(",");
        if (params.length != 3) throw new IllegalArgumentException("invalid Argon2id parameters");
        int memory = parseBounded(params[0], "m=", MAX_ACCEPTED_MEMORY_KIB);
        int iterations = parseBounded(params[1], "t=", MAX_ACCEPTED_ITERATIONS);
        int parallelism = parseBounded(params[2], "p=", MAX_ACCEPTED_PARALLELISM);
        byte[] salt = Base64.getDecoder().decode(pad(parts[4]));
        byte[] hash = Base64.getDecoder().decode(pad(parts[5]));
        if (salt.length < 16 || hash.length != HASH_BYTES) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
            throw new IllegalArgumentException("invalid Argon2id payload lengths");
        }
        return new Parsed(memory, iterations, parallelism, salt, hash);
    }

    private static int parseBounded(String value, String prefix, int maximum) {
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("invalid Argon2id parameter");
        int parsed;
        try {
            parsed = Integer.parseInt(value.substring(prefix.length()));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Argon2id parameter", invalid);
        }
        if (parsed <= 0 || parsed > maximum) throw new IllegalArgumentException("unsupported Argon2id work factor");
        return parsed;
    }

    private static String b64(byte[] value) {
        return Base64.getEncoder().withoutPadding().encodeToString(value);
    }

    private static String pad(String value) {
        return value + "=".repeat((4 - value.length() % 4) % 4);
    }

    private record Parsed(int memoryKiB, int iterations, int parallelism, byte[] salt, byte[] hash) {
        void clear() {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
        }
    }
}
