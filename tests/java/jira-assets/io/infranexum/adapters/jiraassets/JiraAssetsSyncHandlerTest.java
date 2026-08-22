package io.infranexum.adapters.jiraassets;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorOutboundPage;
import io.infranexum.integrations.ConnectorOutboundRecord;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncBatchResult;
import io.infranexum.integrations.ConnectorSyncDirection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JiraAssetsSyncHandlerTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-prod");
    private static final DomainIdentifier RUN = id("018f0d34-2c00-7000-8000-000000000001");
    private static final String ASSET = "018f0d34-2c00-7000-8000-000000000002";
    private static final JiraAssetsMutationSettings SETTINGS = new JiraAssetsMutationSettings(
            KEY, "23", "InfraNexum ID", "id", Map.of("id", "135", "asset_type", "144"), 50);

    @Test
    void createsThenReplaysAsUpdateUsingStableIdentity() {
        RecordingMutationPort jira = new RecordingMutationPort();
        jira.matches.add(Optional.empty());
        jira.matches.add(Optional.of(remote("100")));
        var source = new StaticSource(new ConnectorOutboundPage(List.of(record()), "cursor-1", true));
        JiraAssetsSyncHandler handler = new JiraAssetsSyncHandler(SETTINGS, source, jira);
        ConnectorSyncBatchContext context = context();

        ConnectorSyncBatchResult first = handler.synchronize(context);
        ConnectorSyncBatchResult replay = handler.synchronize(context);

        assertEquals(ConnectorSyncBatchResult.Outcome.APPLIED, first.outcome());
        assertTrue(first.completed());
        assertEquals(1, first.changedCount());
        assertEquals(1, jira.created.size());
        assertEquals(1, jira.updated.size());
        assertTrue(jira.aql.getFirst().contains("objectTypeId = \"23\""));
        assertTrue(jira.aql.getFirst().contains(ASSET));
        assertEquals(Map.of("135", ASSET, "144", "HARDWARE"), jira.created.getFirst());
        assertEquals("100", jira.updatedIds.getFirst());
        assertEquals(first.nextCursor(), replay.nextCursor());
    }

    @Test
    void transientFailureIsRetryableWithoutCompensationEvenAfterEarlierWrite() {
        RecordingMutationPort jira = new RecordingMutationPort();
        jira.matches.add(Optional.empty());
        jira.matches.add(Optional.empty());
        jira.failCreateAt = 2;
        var page = new ConnectorOutboundPage(List.of(record(), new ConnectorOutboundRecord(
                "018f0d34-2c00-7000-8000-000000000003",
                Map.of("id", "018f0d34-2c00-7000-8000-000000000003", "asset_type", "SOFTWARE"), false)), "next", true);
        JiraAssetsSyncHandler handler = new JiraAssetsSyncHandler(SETTINGS, new StaticSource(page), jira);

        ConnectorSyncBatchResult result = handler.synchronize(context());

        assertEquals(ConnectorSyncBatchResult.Outcome.FAILED, result.outcome());
        assertTrue(result.retryable());
        assertFalse(result.compensationRequired());
        assertEquals("JIRA_RETRYABLE_FAILURE", result.failureCode());
    }

    @Test
    void permanentFailureAfterWriteRequiresManualCompensationAndDeletionIsRejected() {
        RecordingMutationPort jira = new RecordingMutationPort();
        jira.matches.add(Optional.empty());
        jira.matches.add(Optional.empty());
        jira.permanentCreateAt = 2;
        ConnectorOutboundRecord deleted = new ConnectorOutboundRecord(
                "018f0d34-2c00-7000-8000-000000000004",
                Map.of("id", "018f0d34-2c00-7000-8000-000000000004", "asset_type", "HARDWARE"), true);
        var records = List.of(record(), new ConnectorOutboundRecord(
                "018f0d34-2c00-7000-8000-000000000003",
                Map.of("id", "018f0d34-2c00-7000-8000-000000000003", "asset_type", "SOFTWARE"), false), deleted);
        JiraAssetsSyncHandler handler = new JiraAssetsSyncHandler(
                SETTINGS, new StaticSource(new ConnectorOutboundPage(records, "next", true)), jira);

        ConnectorSyncBatchResult result = handler.synchronize(context());

        assertEquals("JIRA_PERMANENT_FAILURE", result.failureCode());
        assertTrue(result.compensationRequired());
        assertFalse(result.retryable());
        assertFalse(handler.compensate(nullSafeCompensation()).success());
        assertEquals("MANUAL_COMPENSATION_REQUIRED", handler.compensate(nullSafeCompensation()).failureCode());
    }

    @Test
    void governanceMismatchAndSourceFailureFailClosedBeforeProviderWrite() {
        RecordingMutationPort jira = new RecordingMutationPort();
        JiraAssetsSyncHandler handler = new JiraAssetsSyncHandler(SETTINGS, context -> { throw new IllegalStateException("db"); }, jira);
        ConnectorSyncBatchContext wrongFields = new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, Set.of("id"), false);
        assertEquals("JIRA_GOVERNANCE_MISMATCH", handler.synchronize(wrongFields).failureCode());
        ConnectorSyncBatchContext deletionRequest = new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, SETTINGS.attributeIds().keySet(), true);
        assertEquals("JIRA_GOVERNANCE_MISMATCH", handler.synchronize(deletionRequest).failureCode());
        assertEquals("LOCAL_SOURCE_UNAVAILABLE", handler.synchronize(context()).failureCode());
        assertTrue(jira.aql.isEmpty());
    }

    @Test
    void mutationSettingsRejectUnsafeOrIncompleteMappings() {
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class,
                () -> new JiraAssetsMutationSettings(KEY, "23", "InfraNexum ID", "serial_number", Map.of("serial_number", "1"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class,
                () -> new JiraAssetsMutationSettings(KEY, "23/unsafe", "InfraNexum ID", "id", Map.of("id", "1"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class,
                () -> new JiraAssetsMutationSettings(KEY, "23", "Bad\"Attr", "id", Map.of("id", "1"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class,
                () -> new JiraAssetsMutationSettings(KEY, "23", "InfraNexum ID", "id", Map.of("asset_type", "1"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class,
                () -> new JiraAssetsMutationSettings(KEY, "23", "InfraNexum ID", "id", Map.of("id", "1"), 201));
    }

    private static ConnectorSyncBatchContext context() {
        return new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, SETTINGS.attributeIds().keySet(), false);
    }

    private static ConnectorOutboundRecord record() {
        return new ConnectorOutboundRecord(ASSET, Map.of("id", ASSET, "asset_type", "HARDWARE"), false);
    }

    private static JiraAssetsConnector.RemoteObject remote(String id) {
        return new JiraAssetsConnector.RemoteObject(id, "global-" + id, "KEY-" + id, "Asset", "23", "Asset");
    }

    private static io.infranexum.integrations.ConnectorSyncCompensationContext nullSafeCompensation() {
        var run = new io.infranexum.integrations.ConnectorSyncRun(
                RUN, KEY, "jira-assets", ConnectorSyncDirection.OUTBOUND,
                io.infranexum.integrations.ConnectorRollbackStrategy.MANUAL,
                io.infranexum.integrations.ConnectorSyncRunStatus.COMPENSATING,
                "idem-0001", "a".repeat(64), SETTINGS.attributeIds().keySet(), false, 1, 0, 1, null,
                RUN, RUN, java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:01Z"), null, null);
        return new io.infranexum.integrations.ConnectorSyncCompensationContext(run, null, null, "FAILURE");
    }

    private static DomainIdentifier id(String value) { return new DomainIdentifier(UUID.fromString(value)); }

    private record StaticSource(ConnectorOutboundPage page) implements io.infranexum.integrations.ConnectorOutboundSource {
        @Override public ConnectorOutboundPage read(ConnectorSyncBatchContext context) { return page; }
    }

    private static final class RecordingMutationPort implements JiraAssetsMutationPort {
        private final List<Optional<JiraAssetsConnector.RemoteObject>> matches = new ArrayList<>();
        private final List<String> aql = new ArrayList<>();
        private final List<Map<String, String>> created = new ArrayList<>();
        private final List<Map<String, String>> updated = new ArrayList<>();
        private final List<String> updatedIds = new ArrayList<>();
        private int createCalls;
        private int failCreateAt;
        private int permanentCreateAt;

        @Override public Optional<JiraAssetsConnector.RemoteObject> findUnique(String query) {
            aql.add(query);
            return matches.removeFirst();
        }
        @Override public JiraAssetsConnector.RemoteMutationObject create(String objectTypeId, Map<String, String> attributes) {
            createCalls++;
            if (createCalls == failCreateAt) throw new JiraAssetsUnavailableException("outage");
            if (createCalls == permanentCreateAt) throw new JiraAssetsProtocolException("bad request");
            created.add(attributes);
            return new JiraAssetsConnector.RemoteMutationObject(Integer.toString(100 + createCalls));
        }
        @Override public JiraAssetsConnector.RemoteMutationObject update(String objectId, String objectTypeId, Map<String, String> attributes) {
            updatedIds.add(objectId);
            updated.add(attributes);
            return new JiraAssetsConnector.RemoteMutationObject(objectId);
        }
    }
}
