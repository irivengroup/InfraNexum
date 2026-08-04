package io.infranexum.server.platform;

import io.infranexum.core.capabilities.ActivationState;
import io.infranexum.core.capabilities.CapabilityCode;
import io.infranexum.core.capabilities.CapabilityDecision;
import io.infranexum.core.capabilities.CapabilityEnvironment;
import io.infranexum.core.capabilities.CapabilityRegistry;
import io.infranexum.core.capabilities.CapabilitySnapshot;
import io.infranexum.core.capabilities.QuotaAllocationPlan;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime capability view refreshed only from the authoritative entitlement decision. */
public final class PlatformCapabilityService {
    private final CapabilityRegistry registry;
    private final PlatformCapabilityProperties properties;
    private final QuotaCatalog quotaCatalog;
    private final AtomicReference<View> current;

    /** Compatibility constructor used by isolated controller tests. */
    public PlatformCapabilityService(
            CapabilityRegistry registry,
            CapabilityEnvironment environment,
            QuotaAllocationPlan quotaPlan) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = null;
        this.quotaCatalog = null;
        this.current = new AtomicReference<>(new View(
                environment, registry.evaluate(environment), Objects.requireNonNull(quotaPlan, "quotaPlan")));
    }

    public PlatformCapabilityService(
            CapabilityRegistry registry,
            PlatformCapabilityProperties properties,
            QuotaCatalog quotaCatalog,
            boolean deferUntilEntitlements) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.quotaCatalog = Objects.requireNonNull(quotaCatalog, "quotaCatalog");
        if (deferUntilEntitlements) {
            this.current = new AtomicReference<>();
        } else {
            CapabilityEnvironment initial = properties.toEnvironment();
            this.current = new AtomicReference<>(new View(
                    initial, registry.evaluate(initial), quotaCatalog.allocate(
                            properties.profile(), properties.allocationTier(),
                            properties.catalogVersion(), properties.quotaOverrides())));
        }
    }

    public synchronized void applyEntitlementStatus(EntitlementRuntimeStatus status) {
        Objects.requireNonNull(status, "status");
        if (properties == null || quotaCatalog == null) {
            throw new IllegalStateException("static capability service cannot accept entitlement refreshes");
        }
        ActivationState activationState = switch (status.phase()) {
            case EVALUATION, CONVERSION_REQUIRED -> ActivationState.NOT_REQUIRED;
            case ACTIVE -> ActivationState.ACTIVE;
            case GRACE -> ActivationState.GRACE;
            case HARD_STOPPED -> ActivationState.LOCKED;
        };
        CapabilityEnvironment environment = properties.toEnvironment(
                status.profile(), status.allocationTier(), status.entitledCapabilities(), activationState);
        QuotaAllocationPlan quotaPlan = quotaCatalog.allocate(
                status.profile(), status.allocationTier(), properties.catalogVersion(), status.quotaOverrides());
        current.set(new View(environment, registry.evaluate(environment), quotaPlan));
    }

    public CapabilitySnapshot snapshot() {
        return requireView().snapshot();
    }

    public CapabilityDecision explain(String code) {
        View view = requireView();
        return registry.evaluate(new CapabilityCode(code), view.environment());
    }

    public QuotaAllocationPlan quotaPlan() {
        return requireView().quotaPlan();
    }

    private View requireView() {
        View view = current.get();
        if (view == null) {
            throw new IllegalStateException("capability service is awaiting the authoritative entitlement decision");
        }
        return view;
    }

    private record View(
            CapabilityEnvironment environment,
            CapabilitySnapshot snapshot,
            QuotaAllocationPlan quotaPlan) {
        private View {
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(quotaPlan, "quotaPlan");
        }
    }
}
