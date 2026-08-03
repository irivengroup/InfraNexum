package io.infranexum.core.capabilities;

/** Safe domain error raised when an augmentative operation would exceed a quota. */
public final class QuotaExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient QuotaDecision decision;

    public QuotaExceededException(QuotaDecision decision) {
        super("quota allocation refused: " + java.util.Objects.requireNonNull(decision, "decision").quotaKey());
        this.decision = decision;
    }

    public QuotaDecision decision() {
        return decision;
    }
}
