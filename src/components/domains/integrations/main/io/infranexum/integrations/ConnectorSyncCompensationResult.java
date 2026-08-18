package io.infranexum.integrations;

/** Explicit compensation outcome; a failed compensation is never reported as a successful synchronization. */
public record ConnectorSyncCompensationResult(boolean success, String failureCode) {
    public ConnectorSyncCompensationResult {
        if (success && failureCode != null) throw new IllegalArgumentException("successful compensation cannot have failureCode");
        if (!success && (failureCode == null || !failureCode.matches("^[A-Z0-9_:-]{1,64}$"))) {
            throw new IllegalArgumentException("failed compensation requires stable failureCode");
        }
    }
    public static ConnectorSyncCompensationResult succeeded() { return new ConnectorSyncCompensationResult(true, null); }
    public static ConnectorSyncCompensationResult failed(String code) { return new ConnectorSyncCompensationResult(false, code); }
}
