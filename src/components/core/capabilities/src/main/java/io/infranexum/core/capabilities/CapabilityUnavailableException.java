package io.infranexum.core.capabilities;

/** Safe domain exception for unavailable capabilities. */
public final class CapabilityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient CapabilityDecision decision;

    public CapabilityUnavailableException(CapabilityDecision decision) {
        super("capability unavailable: "
                + java.util.Objects.requireNonNull(decision, "decision").capabilityCode()
                + " (" + decision.reasonCode() + ")");
        this.decision = decision;
    }

    public CapabilityDecision decision() {
        return decision;
    }
}
