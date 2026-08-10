package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ActivationManifestJsonCodecTest {
    private final ActivationManifestJsonCodec codec =
            new ActivationManifestJsonCodec(new ObjectMapper(), 64 * 1024);

    @Test
    void decodesTheCanonicalManifestWithoutChangingTheSignedPayload() {
        var expected = ActivationTestFixtures.manifest();
        var actual = codec.decode(expected.canonicalDocument());
        assertEquals(expected.payload(), actual.payload());
        assertEquals(expected.signature(), actual.signature());
    }

    @Test
    void rejectsUnknownFieldsMalformedTypesDuplicatesAndOversizedDocuments() {
        String document = ActivationTestFixtures.manifest().canonicalDocument();
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(document.substring(0, document.length() - 1) + ",\"unknown\":true}"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(document.replace("\"host_limit\":10", "\"host_limit\":\"10\"")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(document.replace(
                        "\"capabilities\":[\"iam.local-auth\"]",
                        "\"capabilities\":[\"iam.local-auth\",\"iam.local-auth\"]")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(document.replace("\"iam.users.max\":10", "\"iam.users.max\":-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationManifestJsonCodec(new ObjectMapper(), 1024).decode("x".repeat(1025)));
        assertThrows(NullPointerException.class,
                () -> new ActivationManifestJsonCodec(null, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ActivationManifestJsonCodec(new ObjectMapper(), 512));
    }
}
