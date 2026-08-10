package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainErrorCode;
import java.io.Serial;
import java.util.Objects;

/** Fail-closed validation error with a stable external code and no sensitive payload disclosure. */
public final class ActivationValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String codeValue;
    private transient DomainErrorCode code;

    public ActivationValidationException(DomainErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.codeValue = code.value();
    }

    public DomainErrorCode code() {
        if (code == null) {
            code = new DomainErrorCode(codeValue);
        }
        return code;
    }
}
