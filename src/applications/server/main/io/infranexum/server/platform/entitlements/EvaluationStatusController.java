package io.infranexum.server.platform.entitlements;

import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the normative, read-only evaluation status without leaking signed documents or secrets. */
@ConditionalOnProperty(
        name = "infranexum.entitlements.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/platform/evaluation")
public final class EvaluationStatusController {
    private final EntitlementRuntimeAuthority authority;

    public EvaluationStatusController(EntitlementRuntimeAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @GetMapping("/status")
    public ResponseEntity<EvaluationStatusResponse> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(EvaluationStatusResponse.from(authority.currentStatus()));
    }
}
