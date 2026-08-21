package io.infranexum.dcim.physical.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.physical.domain.CableType;
import java.math.BigDecimal;

/** Application command for connecting two existing physical ports with inventoried cable metadata. */
public record ConnectPortsCommand(
        DomainIdentifier organizationId,
        DomainIdentifier subdivisionId,
        DomainIdentifier portAId,
        DomainIdentifier portBId,
        String label,
        CableType cableType,
        BigDecimal lengthMeters,
        DomainIdentifier manufacturerPartnerId,
        String manufacturerReference) {
    public ConnectPortsCommand(
            DomainIdentifier organizationId, DomainIdentifier subdivisionId,
            DomainIdentifier portAId, DomainIdentifier portBId, String label) {
        this(organizationId, subdivisionId, portAId, portBId, label, CableType.OTHER,
                BigDecimal.ONE, null, null);
    }
}
