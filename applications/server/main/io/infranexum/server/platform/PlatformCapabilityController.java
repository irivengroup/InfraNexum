package io.infranexum.server.platform;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only platform registry API; unavailable feature routes are registered elsewhere only when allowed. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformCapabilityController {
    private final PlatformCapabilityService service;

    public PlatformCapabilityController(PlatformCapabilityService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @GetMapping("/capabilities")
    ResponseEntity<CapabilitySnapshotResponse> capabilities() {
        return noStore(CapabilitySnapshotResponse.from(service.snapshot()));
    }

    @GetMapping("/capabilities/{code}")
    ResponseEntity<CapabilityDecisionResponse> capability(@PathVariable String code) {
        return noStore(CapabilityDecisionResponse.from(service.explain(code)));
    }

    @GetMapping("/quotas")
    ResponseEntity<QuotaPlanResponse> quotas() {
        return noStore(QuotaPlanResponse.from(service.quotaPlan()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
