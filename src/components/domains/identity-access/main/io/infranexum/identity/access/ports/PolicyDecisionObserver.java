package io.infranexum.identity.access.ports;

import io.infranexum.identity.access.domain.PolicyEvaluationResult;
import java.time.Duration;

/** Observability port keeping metric libraries outside the IAM domain. */
@FunctionalInterface
public interface PolicyDecisionObserver {
    void record(PolicyEvaluationResult result, Duration elapsed, boolean cacheHit);
}
