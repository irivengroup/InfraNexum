package io.infranexum.core.entitlements;

import io.infranexum.core.contracts.DomainErrorCode;

/** Stable failure codes used by API, CLI, workers and service startup guards. */
public final class EntitlementErrorCodes {
    public static final DomainErrorCode LITE_CONVERSION_REQUIRED =
            new DomainErrorCode("INFRANEXUM_LITE_CONVERSION_REQUIRED");
    public static final DomainErrorCode LITE_HARD_STOPPED =
            new DomainErrorCode("INFRANEXUM_LITE_HARD_STOPPED");
    public static final DomainErrorCode ACTIVATION_REQUIRED =
            new DomainErrorCode("INFRANEXUM_ACTIVATION_REQUIRED");
    public static final DomainErrorCode ACTIVATION_INVALID =
            new DomainErrorCode("INFRANEXUM_ACTIVATION_INVALID");
    public static final DomainErrorCode ACTIVATION_EXPIRED =
            new DomainErrorCode("INFRANEXUM_ACTIVATION_EXPIRED");
    public static final DomainErrorCode ACTIVATION_REVOKED =
            new DomainErrorCode("INFRANEXUM_ACTIVATION_REVOKED");
    public static final DomainErrorCode CLOCK_ROLLBACK_DETECTED =
            new DomainErrorCode("INFRANEXUM_CLOCK_ROLLBACK_DETECTED");

    private EntitlementErrorCodes() {}
}
