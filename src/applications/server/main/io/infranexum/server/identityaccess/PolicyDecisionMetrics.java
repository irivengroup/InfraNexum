package io.infranexum.server.identityaccess;

import io.infranexum.identity.access.domain.PolicyEvaluationResult;
import io.infranexum.identity.access.ports.PolicyDecisionObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

/** Low-cardinality metrics for PDP decisions and latency. */
final class PolicyDecisionMetrics implements PolicyDecisionObserver {
    private final MeterRegistry registry;

    PolicyDecisionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void record(PolicyEvaluationResult result, Duration elapsed, boolean cacheHit) {
        String decision = result.decision().name().toLowerCase(java.util.Locale.ROOT);
        String cached = Boolean.toString(cacheHit);
        registry.counter("infranexum.iam.pdp.decisions", "decision", decision, "cache_hit", cached).increment();
        registry.timer("infranexum.iam.pdp.duration", "decision", decision, "cache_hit", cached).record(elapsed);
        if (!result.permitted()) {
            registry.counter("infranexum.iam.pdp.refusals", "decision", decision).increment();
        }
    }
}
