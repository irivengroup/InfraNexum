package io.infranexum.itam.asset.domain;

import java.util.Objects;

/** Domain conflict exposed as a stable problem code at API/CLI boundaries. */
public final class AssetConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public AssetConflictException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() { return code; }
}
