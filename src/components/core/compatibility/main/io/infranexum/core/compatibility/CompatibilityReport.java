package io.infranexum.core.compatibility;

import java.util.List;
import java.util.Objects;

/** Deterministic compatibility report persisted as part of publication evidence. */
public record CompatibilityReport(CompatibilityVerdict verdict, List<String> issues) {
    public CompatibilityReport {
        Objects.requireNonNull(verdict, "verdict");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (verdict == CompatibilityVerdict.COMPATIBLE && !issues.isEmpty()) {
            throw new IllegalArgumentException("compatible report cannot contain issues");
        }
        if (verdict != CompatibilityVerdict.COMPATIBLE && issues.isEmpty()) {
            throw new IllegalArgumentException("non-compatible report requires at least one issue");
        }
    }

    public static CompatibilityReport compatible() {
        return new CompatibilityReport(CompatibilityVerdict.COMPATIBLE, List.of());
    }
}
