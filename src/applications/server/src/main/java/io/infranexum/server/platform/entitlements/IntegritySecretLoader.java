package io.infranexum.server.platform.entitlements;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Loads the HMAC integrity key from a root-readable external secret file. */
public final class IntegritySecretLoader {
    public SecretKey load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            rejectBroadPosixPermissions(path);
            String encoded = Files.readString(path, StandardCharsets.US_ASCII).strip();
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length < 32 || key.length > 64) {
                throw new IllegalArgumentException("integrity HMAC key must contain 32 to 64 bytes");
            }
            return new SecretKeySpec(key, "HmacSHA256");
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("cannot load entitlement integrity key", error);
        }
    }

    private static void rejectBroadPosixPermissions(Path path) throws java.io.IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IllegalStateException("entitlement integrity key permissions must not grant group or other access");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms rely on their native ACL model.
        }
    }
}
