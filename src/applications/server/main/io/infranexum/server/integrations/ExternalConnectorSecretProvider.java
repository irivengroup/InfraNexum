package io.infranexum.server.integrations;

import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.integrations.ConnectorSecretProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Set;

/** Resolves connector HMAC secrets from environment variables or absolute secret files only. */
final class ExternalConnectorSecretProvider implements ConnectorSecretProvider {
    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_SECRET_BYTES = 4_096;

    @Override
    public byte[] resolve(String reference) {
        if (reference == null) throw new ConfigurationException("connector secret reference is missing");
        if (reference.startsWith("env:")) {
            String name = reference.substring(4);
            if (!name.matches("[A-Z][A-Z0-9_]{1,127}")) {
                throw new ConfigurationException("connector secret environment variable name is invalid");
            }
            String value = System.getenv(name);
            if (value == null) {
                throw new ConfigurationException("connector secret environment variable is not defined: " + name);
            }
            return bounded(value.getBytes(StandardCharsets.UTF_8));
        }
        if (reference.startsWith("file:")) {
            Path path = Path.of(reference.substring(5)).normalize();
            if (!path.isAbsolute()) throw new ConfigurationException("connector secret file must be absolute");
            return readBoundedRegularFile(path);
        }
        throw new ConfigurationException("connector secret reference must use env: or file:");
    }

    /** Opens the path without following symlinks and never buffers more than the accepted secret size plus one byte. */
    private static byte[] readBoundedRegularFile(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new ConfigurationException("connector secret file is not a regular file");
            }
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(MAX_SECRET_BYTES + 1);
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // The bounded buffer prevents an oversized secret file from becoming an unbounded allocation.
                }
                if (buffer.position() > MAX_SECRET_BYTES) {
                    Arrays.fill(buffer.array(), (byte) 0);
                    throw new ConfigurationException("connector secret must contain 32..4096 bytes");
                }
                byte[] value = Arrays.copyOf(buffer.array(), buffer.position());
                Arrays.fill(buffer.array(), (byte) 0);
                return bounded(value);
            }
        } catch (IOException failure) {
            throw new ConfigurationException("connector secret file cannot be read");
        }
    }

    private static byte[] bounded(byte[] value) {
        if (value.length < MIN_SECRET_BYTES || value.length > MAX_SECRET_BYTES) {
            Arrays.fill(value, (byte) 0);
            throw new ConfigurationException("connector secret must contain 32..4096 bytes");
        }
        return value;
    }
}
