package io.infranexum.core.audit;

/** Result of independently recomputing one scope's hash chain. */
public record AuditChainVerification(boolean valid, long verifiedRecords, long failingSequence, String headHash) {
    public AuditChainVerification {
        if (verifiedRecords < 0 || failingSequence < 0) throw new IllegalArgumentException("audit verification counters must be non-negative");
        if (headHash == null || !headHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid audit head hash");
        if (valid && failingSequence != 0) throw new IllegalArgumentException("valid chain cannot have a failing sequence");
        if (!valid && failingSequence == 0) throw new IllegalArgumentException("invalid chain requires a failing sequence");
    }
}
