package io.infranexum.adapters.servicenow;

import java.util.Map;
import java.util.Optional;

/** Narrow write/read port used by the governed ServiceNow synchronization handler. */
public interface ServiceNowMutationPort {
    Optional<ServiceNowConnector.RemoteMutationObject> findUnique(String identityField, String identity);
    ServiceNowConnector.RemoteMutationObject create(Map<String, String> fields);
    ServiceNowConnector.RemoteMutationObject update(String sysId, Map<String, String> fields);
}
