
package io.infranexum.server.system;

import io.infranexum.core.contracts.ComponentKind;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the minimal non-sensitive system contract required for bootstrap diagnostics. */
@RestController
@RequestMapping("/api/v1/system")
public final class SystemController {
    private final ServerRuntimeProperties properties;

    public SystemController(ServerRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/build")
    public BuildInfoResponse build() {
        return new BuildInfoResponse(
                "InfraNexum",
                properties.version(),
                properties.architectureBaseline(),
                ComponentKind.SERVER,
                properties.instanceId(),
                properties.mode(),
                properties.region(),
                properties.site());
    }
}
