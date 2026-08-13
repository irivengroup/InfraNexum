package io.infranexum.identity.local.ports;

public interface PasswordHasher {
    String hash(char[] password);
    boolean verify(char[] password, String encodedHash);
    boolean needsRehash(String encodedHash);
    void consumeEquivalentWork(char[] password);
}
