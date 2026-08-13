package io.infranexum.server.identity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class LocalAuthSecretReader {
    private LocalAuthSecretReader() {}

    static char[] read(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("local-auth bootstrap password file is unavailable", failure);
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(buffer);
            while (decoded.hasRemaining() && Character.isWhitespace(decoded.get(decoded.limit() - 1))) {
                decoded.limit(decoded.limit() - 1);
            }
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException("local-auth bootstrap password file is not valid UTF-8", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
