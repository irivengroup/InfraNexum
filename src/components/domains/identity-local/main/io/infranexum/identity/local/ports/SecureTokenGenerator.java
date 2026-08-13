package io.infranexum.identity.local.ports;

public interface SecureTokenGenerator {
    String nextToken();
    String sha256(String token);
}
