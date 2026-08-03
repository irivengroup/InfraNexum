
package io.infranexum.server.system;

import io.infranexum.core.contracts.ComponentKind;
import io.infranexum.core.contracts.RuntimeMode;

/** Public diagnostic response; it intentionally contains no secret or host credential. */
public record BuildInfoResponse(
        String product,
        String version,
        String architectureBaseline,
        ComponentKind component,
        String instanceId,
        RuntimeMode mode,
        String region,
        String site) {}
