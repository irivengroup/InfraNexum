package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegritySecretLoaderTest {
    @TempDir Path directory;

    @Test
    void loadsARestrictedBase64HmacKey() throws Exception {
        byte[] expected = new byte[32];
        java.util.Arrays.fill(expected, (byte) 7);
        Path key = directory.resolve("integrity.key");
        Files.writeString(key, Base64.getEncoder().encodeToString(expected));
        restrict(key);
        assertArrayEquals(expected, new IntegritySecretLoader().load(key).getEncoded());
    }

    @Test
    void rejectsShortInvalidAndBroadlyReadableSecrets() throws Exception {
        Path shortKey = directory.resolve("short.key");
        Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]));
        restrict(shortKey);
        assertThrows(IllegalArgumentException.class, () -> new IntegritySecretLoader().load(shortKey));

        Path invalid = directory.resolve("invalid.key");
        Files.writeString(invalid, "not-base64");
        restrict(invalid);
        assertThrows(IllegalArgumentException.class, () -> new IntegritySecretLoader().load(invalid));

        Path broad = directory.resolve("broad.key");
        Files.writeString(broad, Base64.getEncoder().encodeToString(new byte[32]));
        try {
            Files.setPosixFilePermissions(broad, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
            assertThrows(IllegalStateException.class, () -> new IntegritySecretLoader().load(broad));
        } catch (UnsupportedOperationException ignored) {
            // The permission branch is not applicable on non-POSIX test platforms.
        }
    }

    private static void restrict(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Native ACLs are used on non-POSIX test platforms.
        }
    }
}
