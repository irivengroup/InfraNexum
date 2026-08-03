package io.infranexum.core.capabilities;

/** Defense-in-depth domain guard for quota-controlled mutations. */
public final class QuotaGuard {
    private QuotaGuard() {}

    public static void requireAllowed(QuotaDecision decision) {
        if (!java.util.Objects.requireNonNull(decision, "decision").allowed()) {
            throw new QuotaExceededException(decision);
        }
    }
}
