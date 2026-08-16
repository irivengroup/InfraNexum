package io.infranexum.server.http;

import io.infranexum.server.observability.SensitiveDataRedactor;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/** Shared deterministic construction of the canonical problem boundary in Server tests. */
public final class ApiProblemTestFixtures {
    private ApiProblemTestFixtures() {}

    public static ApiProblemSupport support(Clock clock) {
        return new ApiProblemSupport(clock, new SensitiveDataRedactor(), new ObjectMapper());
    }
}
