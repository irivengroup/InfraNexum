package io.infranexum.identity.local.domain;

import java.io.Serial;
import java.util.List;

/** Deterministic non-secret failure emitted when a local password violates policy. */
public final class LocalPasswordPolicyException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final java.util.ArrayList<String> violations;

    public LocalPasswordPolicyException(List<String> violations) {
        super("local password policy violation");
        this.violations = new java.util.ArrayList<>(violations);
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }
}
