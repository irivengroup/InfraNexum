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
    @Test
    void argon2idRehashDecisionCoversEachIndependentWorkFactor() {
        char[] password = "Long-Secure-Password!Aa1".toCharArray();
        String encoded = hasher.hash(password);
        assertFalse(hasher.needsRehash(encoded));
        assertTrue(hasher.needsRehash(encoded.replace("m=65536", "m=32768")));
        assertTrue(hasher.needsRehash(encoded.replace("t=3", "t=2")));
        assertTrue(hasher.needsRehash(encoded.replace("p=1", "p=2")));
    }

    @Test
    void argon2idParserRejectsNumericSyntaxAndEveryPayloadLengthBoundary() {
        String validSalt = "MDEyMzQ1Njc4OWFiY2RlZg";
        String validHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String shortSalt = "MDEyMzQ1Njc4OWFiY2Rl";
        String shortHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String longHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        for (String invalid : new String[] {
                "$argon2id$v=19$m=x,t=3,p=1$" + validSalt + "$" + validHash,
                "$argon2id$v=19$m=65536,t=x,p=1$" + validSalt + "$" + validHash,
                "$argon2id$v=19$m=65536,t=3,p=x$" + validSalt + "$" + validHash,
                "$argon2id$v=19$m=65536,t=3,p=1$" + shortSalt + "$" + validHash,
                "$argon2id$v=19$m=65536,t=3,p=1$" + validSalt + "$" + shortHash,
                "$argon2id$v=19$m=65536,t=3,p=1$" + validSalt + "$" + longHash
        }) {
            assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash(invalid), invalid);
        }
    }

    @Test
    void argon2idParserRejectsEachParameterPrefixAndWorkFactorBoundary() {
        String salt = "MDEyMzQ1Njc4OWFiY2RlZg";
        String hash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        for (String parameters : new String[] {
                "x=65536,t=3,p=1", "m=65536,x=3,p=1", "m=65536,t=3,x=1",
                "m=-1,t=3,p=1", "m=262145,t=3,p=1",
                "m=65536,t=0,p=1", "m=65536,t=11,p=1",
                "m=65536,t=3,p=0", "m=65536,t=3,p=9"
        }) {
            String encoded = "$argon2id$v=19$" + parameters + "$" + salt + "$" + hash;
            assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash(encoded), parameters);
        }
    }

}
