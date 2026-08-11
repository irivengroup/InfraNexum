package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainErrorCode;
import java.io.Serial;
import java.util.Objects;

/** Fail-closed access denial emitted by service startup and mutation guards. */
public final class EntitlementAccessException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String codeValue;
    private transient DomainErrorCode code;

    public EntitlementAccessException(DomainErrorCode code, String message) {
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
