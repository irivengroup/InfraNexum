package io.infranexum.adapters.jiraassets;

import java.util.Map;
import java.util.Optional;

/** Narrow write/read port used by the governed Jira synchronization handler. */
public interface JiraAssetsMutationPort {
    Optional<JiraAssetsConnector.RemoteObject> findUnique(String aql);
    JiraAssetsConnector.RemoteMutationObject create(String objectTypeId, Map<String, String> attributes);
    JiraAssetsConnector.RemoteMutationObject update(String objectId, String objectTypeId, Map<String, String> attributes);
}
