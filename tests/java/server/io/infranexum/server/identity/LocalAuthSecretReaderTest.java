package io.infranexum.server.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class LocalAuthSecretReaderTest {
    @TempDir Path temp;

    @Test
    void readsUtf8AndStripsOnlyTrailingWhitespace() throws Exception {
        Path secret = temp.resolve("admin");
        Files.writeString(secret, " Leading-Secret!Aa1  \r\n");
        assertArrayEquals(" Leading-Secret!Aa1".toCharArray(), LocalAuthSecretReader.read(secret));
    }

    @Test
    void refusesMissingOrEmptySecrets() throws Exception {
        assertThrows(IllegalStateException.class, () -> LocalAuthSecretReader.read(temp.resolve("missing")));
        Path empty = temp.resolve("empty");
        Files.writeString(empty, " \r\n\t");
        assertThrows(IllegalStateException.class, () -> LocalAuthSecretReader.read(empty));
    }
}
