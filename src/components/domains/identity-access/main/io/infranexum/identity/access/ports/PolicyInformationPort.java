package io.infranexum.identity.access.ports;

import io.infranexum.identity.access.domain.PolicyAttributeBag;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import java.time.Instant;

/** PIP contract for reconstructing all authority attributes server-side. */
@FunctionalInterface
public interface PolicyInformationPort {
    PolicyAttributeBag resolve(PolicyEvaluationRequest request, Instant at);
}
