package io.infranexum.integrations;

/** Resolves webhook signing material from an external secret source; callers must not persist returned bytes. */
@FunctionalInterface
public interface ConnectorSecretProvider {
    byte[] resolve(String secretReference);
}
