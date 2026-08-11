package io.infranexum.core.entitlements;

import java.time.Instant;

/** Builds a validation context from authoritative runtime catalogues and durable sequence state. */
@FunctionalInterface
public interface ActivationContextFactory {
    ActivationValidationContext create(InstallationIdentity identity, AcceptedSequence sequence, Instant now);
}
