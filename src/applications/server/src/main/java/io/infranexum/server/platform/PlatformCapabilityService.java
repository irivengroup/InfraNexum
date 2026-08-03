package io.infranexum.server.platform;

import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityDecision;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.CapabilitySnapshot;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import java.util.Objects;

/** Immutable startup snapshot used by all public and internal surfaces. */
public final class PlatformCapabilityService {
    private final CapabilityRegistry registry;
    private final CapabilityEnvironment environment;
    private final CapabilitySnapshot snapshot;
    private final QuotaAllocationPlan quotaPlan;

    public PlatformCapabilityService(
            CapabilityRegistry registry,
            CapabilityEnvironment environment,
            QuotaAllocationPlan quotaPlan) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.quotaPlan = Objects.requireNonNull(quotaPlan, "quotaPlan");
        this.snapshot = registry.evaluate(environment);
    }

    public CapabilitySnapshot snapshot() {
        return snapshot;
    }

    public CapabilityDecision explain(String code) {
        return registry.evaluate(new CapabilityCode(code), environment);
    }

    public QuotaAllocationPlan quotaPlan() {
        return quotaPlan;
    }
}
