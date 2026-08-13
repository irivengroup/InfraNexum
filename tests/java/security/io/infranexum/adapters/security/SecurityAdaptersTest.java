package io.infranexum.adapters.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SecurityAdaptersTest {
    private static BouncyCastleArgon2idPasswordHasher hasher;

    @BeforeAll
    static void initializeHasher() {
        hasher = new BouncyCastleArgon2idPasswordHasher(new SecureRandom(new byte[] {9,8,7,6}));
    }

    @Test
    void argon2idProducesSaltedPhcHashesAndVerifiesWithoutCustomCryptography() {
        char[] password = "Long-Secure-Password!Aa1".toCharArray();
        String first = hasher.hash(password);
        String second = hasher.hash(password);
        assertTrue(first.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));
        assertNotEquals(first, second);
        assertTrue(hasher.verify(password, first));
        assertFalse(hasher.verify("Wrong-Password!Aa1".toCharArray(), first));
        assertFalse(hasher.needsRehash(first));
        assertTrue(hasher.needsRehash(first.replace("m=65536", "m=32768")));
        hasher.consumeEquivalentWork("Unknown-Account!Aa1".toCharArray());
    }

    @Test
    void argon2idParserFailsClosedForMalformedOrUnsafeEncodings() {
        for (String invalid : new String[] {
                "", "$argon2i$v=19$m=65536,t=3,p=1$AA$AA",
                "$argon2id$v=16$m=65536,t=3,p=1$AA$AA",
                "$argon2id$v=19$m=65536,t=3$AA$AA",
                "$argon2id$v=19$x=1,t=3,p=1$AA$AA",
                "$argon2id$v=19$m=0,t=3,p=1$AA$AA",
                "$argon2id$v=19$m=2147483647,t=3,p=1$MDEyMzQ1Njc4OWFiY2RlZg$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=65536,t=999,p=1$MDEyMzQ1Njc4OWFiY2RlZg$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=65536,t=3,p=999$MDEyMzQ1Njc4OWFiY2RlZg$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "$argon2id$v=19$m=65536,t=3,p=1$not-base64$bad"}) {
            assertThrows(RuntimeException.class, () -> hasher.needsRehash(invalid), invalid);
        }
        assertThrows(NullPointerException.class, () -> hasher.hash(null));
        assertThrows(NullPointerException.class, () -> hasher.verify(new char[0], null));
    }

    @Test
    void tokenGeneratorUses256BitUrlSafeTokensAndStableSha256Fingerprints() {
        SecureRandomTokenGenerator tokens = new SecureRandomTokenGenerator(new SecureRandom(new byte[] {1,2,3}));
        String first = tokens.nextToken();
        String second = tokens.nextToken();
        assertEquals(43, first.length());
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
        assertEquals(64, tokens.sha256(first).length());
        assertEquals(tokens.sha256(first), tokens.sha256(first));
        assertNotEquals(tokens.sha256(first), tokens.sha256(second));
        assertThrows(NullPointerException.class, () -> tokens.sha256(null));
        assertThrows(NullPointerException.class, () -> new SecureRandomTokenGenerator(null));
        assertThrows(NullPointerException.class, () -> new BouncyCastleArgon2idPasswordHasher(null));
    }
}
