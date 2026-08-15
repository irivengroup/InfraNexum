package io.infranexum.dcim.facility.ports;

import io.infranexum.dcim.facility.domain.FacilityKind;

/** Capability/quota decisions consumed by the DCIM domain without profile-name branching. */
public interface FacilityFeaturePolicy {
    boolean facilitiesEnabled();
    long limit(FacilityKind kind);
}
