package io.infranexum.adapters.servicenow;

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

class ServiceNowSyncHandlerTest {
    private static final ConnectorKey KEY = new ConnectorKey("service-now-prod");
    private static final DomainIdentifier RUN = id("018f0d34-2c00-7000-8000-000000000001");
    private static final String ASSET = "018f0d34-2c00-7000-8000-000000000002";
    private static final ServiceNowMutationSettings SETTINGS = new ServiceNowMutationSettings(
            KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_asset_type"), 50);
    private static final ServiceNowMutationSettings TOMBSTONE_SETTINGS = new ServiceNowMutationSettings(
            KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "u_asset_type"), 50,
            new ServiceNowTombstoneSettings("u_infranexum_state", "disposed"));

    @Test
    void createsThenReplaysAsPatchUsingStableIdentity() {
        RecordingMutationPort serviceNow = new RecordingMutationPort();
        serviceNow.matches.add(Optional.empty());
        serviceNow.matches.add(Optional.of(new ServiceNowConnector.RemoteMutationObject(
                "0123456789abcdef0123456789abcdef")));
        ServiceNowSyncHandler handler = new ServiceNowSyncHandler(
                SETTINGS, new StaticSource(new ConnectorOutboundPage(List.of(record()), "cursor-1", true)), serviceNow);

        ConnectorSyncBatchResult first = handler.synchronize(context());
        ConnectorSyncBatchResult replay = handler.synchronize(context());

        assertEquals(ConnectorSyncBatchResult.Outcome.APPLIED, first.outcome());
        assertTrue(first.completed());
        assertEquals(1, first.changedCount());
        assertEquals(1, serviceNow.created.size());
        assertEquals(1, serviceNow.updated.size());
        assertEquals("u_infranexum_id", serviceNow.identityFields.getFirst());
        assertEquals(ASSET, serviceNow.identities.getFirst());
        assertEquals(Map.of("u_infranexum_id", ASSET, "u_asset_type", "HARDWARE"),
                serviceNow.created.getFirst());
        assertEquals("0123456789abcdef0123456789abcdef", serviceNow.updatedIds.getFirst());
        assertEquals(first.nextCursor(), replay.nextCursor());
    }

    @Test
    void transientFailureIsRetryableWithoutCompensationEvenAfterEarlierWrite() {
        RecordingMutationPort serviceNow = new RecordingMutationPort();
        serviceNow.matches.add(Optional.empty());
        serviceNow.matches.add(Optional.empty());
        serviceNow.failCreateAt = 2;
        var page = new ConnectorOutboundPage(List.of(record(), new ConnectorOutboundRecord(
                "018f0d34-2c00-7000-8000-000000000003",
                Map.of("id", "018f0d34-2c00-7000-8000-000000000003", "asset_type", "SOFTWARE"), false)),
                "next", true);
        ServiceNowSyncHandler handler = new ServiceNowSyncHandler(SETTINGS, new StaticSource(page), serviceNow);

        ConnectorSyncBatchResult result = handler.synchronize(context());

        assertEquals(ConnectorSyncBatchResult.Outcome.FAILED, result.outcome());
        assertTrue(result.retryable());
        assertFalse(result.compensationRequired());
        assertEquals("SERVICE_NOW_RETRYABLE_FAILURE", result.failureCode());
    }

    @Test
    void permanentFailureAfterWriteRequiresManualCompensationAndDeletionIsNotPropagated() {
        RecordingMutationPort serviceNow = new RecordingMutationPort();
        serviceNow.matches.add(Optional.empty());
        serviceNow.matches.add(Optional.empty());
        serviceNow.permanentCreateAt = 2;
        var records = List.of(record(), new ConnectorOutboundRecord(
                "018f0d34-2c00-7000-8000-000000000003",
                Map.of("id", "018f0d34-2c00-7000-8000-000000000003", "asset_type", "SOFTWARE"), false));
        ServiceNowSyncHandler handler = new ServiceNowSyncHandler(
                SETTINGS, new StaticSource(new ConnectorOutboundPage(records, "next", true)), serviceNow);

        ConnectorSyncBatchResult result = handler.synchronize(context());

        assertEquals("SERVICE_NOW_PERMANENT_FAILURE", result.failureCode());
        assertTrue(result.compensationRequired());
        assertFalse(result.retryable());
        var compensation = handler.compensate(compensation());
        assertFalse(compensation.success());
        assertEquals("MANUAL_COMPENSATION_REQUIRED", compensation.failureCode());
    }

    @Test
    void governanceMismatchSourceFailureAndDeletedRecordsFailClosed() {
        RecordingMutationPort serviceNow = new RecordingMutationPort();
        ServiceNowSyncHandler unavailable = new ServiceNowSyncHandler(
                SETTINGS, context -> { throw new IllegalStateException("db"); }, serviceNow);
        ConnectorSyncBatchContext wrongFields = new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, Set.of("id"), false);
        assertEquals("SERVICE_NOW_GOVERNANCE_MISMATCH", unavailable.synchronize(wrongFields).failureCode());
        ConnectorSyncBatchContext deletionRequest = new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, SETTINGS.fieldNames().keySet(), true);
        assertEquals("SERVICE_NOW_GOVERNANCE_MISMATCH", unavailable.synchronize(deletionRequest).failureCode());
        assertEquals("LOCAL_SOURCE_UNAVAILABLE", unavailable.synchronize(context()).failureCode());

        var deleted = new ConnectorOutboundRecord(ASSET, Map.of("id", ASSET, "asset_type", "HARDWARE"), true);
        ServiceNowSyncHandler deletion = new ServiceNowSyncHandler(SETTINGS,
                new StaticSource(new ConnectorOutboundPage(List.of(deleted), "next", true)), serviceNow);
        ConnectorSyncBatchResult result = deletion.synchronize(context());
        assertEquals(1, result.rejectedCount());
        assertEquals(0, result.changedCount());
        assertTrue(serviceNow.identities.isEmpty());
    }

    @Test
    void controlledTombstonePatchesExistingCiButNeverCreatesOne() {
        RecordingMutationPort serviceNow = new RecordingMutationPort();
        serviceNow.matches.add(Optional.of(new ServiceNowConnector.RemoteMutationObject(
                "0123456789abcdef0123456789abcdef")));
        serviceNow.matches.add(Optional.empty());
        ConnectorOutboundRecord disposed = new ConnectorOutboundRecord(
                ASSET, Map.of("id", ASSET, "asset_type", "HARDWARE"), true);
        ServiceNowSyncHandler handler = new ServiceNowSyncHandler(
                TOMBSTONE_SETTINGS,
                new StaticSource(new ConnectorOutboundPage(List.of(disposed), "next", true)), serviceNow);
        ConnectorSyncBatchContext deletionContext = new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1,
                TOMBSTONE_SETTINGS.fieldNames().keySet(), true);

        ConnectorSyncBatchResult first = handler.synchronize(deletionContext);
        ConnectorSyncBatchResult absentReplay = handler.synchronize(deletionContext);

        assertEquals(1, first.changedCount());
        assertEquals(Map.of("u_infranexum_state", "disposed"), serviceNow.updated.getFirst());
        assertTrue(serviceNow.created.isEmpty());
        assertEquals(0, absentReplay.changedCount());
        assertEquals(0, absentReplay.rejectedCount());
    }

    @Test
    void mutationSettingsRejectUnsafeIdentityColumnsMappingsAndBatchSizes() {
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "serial_number", Map.of("serial_number", "u_serial"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("asset_type", "u_asset_type"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("id", "sys_id"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("id", "name"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("id", "u_infranexum_id", "asset_type", "bad-field"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id",
                        Map.of("id", "u_infranexum_id", "asset_type", "u_infranexum_id"), 1));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("id", "u_infranexum_id"), 201));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowMutationSettings(KEY, "id", Map.of("id", "u_infranexum_id"), 1,
                        new ServiceNowTombstoneSettings("u_infranexum_id", "disposed")));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowTombstoneSettings("sys_updated_on", "disposed"));
        assertThrows(io.infranexum.core.contracts.ConfigurationException.class, () ->
                new ServiceNowTombstoneSettings("u_state", " disposed "));
    }

    private static ConnectorSyncBatchContext context() {
        return new ConnectorSyncBatchContext(
                RUN, KEY, ConnectorSyncDirection.OUTBOUND, null, 0, 1, SETTINGS.fieldNames().keySet(), false);
    }

    private static ConnectorOutboundRecord record() {
        return new ConnectorOutboundRecord(ASSET, Map.of("id", ASSET, "asset_type", "HARDWARE"), false);
    }

    private static io.infranexum.integrations.ConnectorSyncCompensationContext compensation() {
        var run = new io.infranexum.integrations.ConnectorSyncRun(
                RUN, KEY, "service-now", ConnectorSyncDirection.OUTBOUND,
                io.infranexum.integrations.ConnectorRollbackStrategy.MANUAL,
                io.infranexum.integrations.ConnectorSyncRunStatus.COMPENSATING,
                "idem-0001", "a".repeat(64), SETTINGS.fieldNames().keySet(), false, 1, 0, 1, null,
                RUN, RUN, java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:01Z"), null, null);
        return new io.infranexum.integrations.ConnectorSyncCompensationContext(run, null, null, "FAILURE");
    }

    private static DomainIdentifier id(String value) { return new DomainIdentifier(UUID.fromString(value)); }

    private record StaticSource(ConnectorOutboundPage page) implements io.infranexum.integrations.ConnectorOutboundSource {
        @Override public ConnectorOutboundPage read(ConnectorSyncBatchContext context) { return page; }
    }

    private static final class RecordingMutationPort implements ServiceNowMutationPort {
        private final List<Optional<ServiceNowConnector.RemoteMutationObject>> matches = new ArrayList<>();
        private final List<String> identityFields = new ArrayList<>();
        private final List<String> identities = new ArrayList<>();
        private final List<Map<String, String>> created = new ArrayList<>();
        private final List<Map<String, String>> updated = new ArrayList<>();
        private final List<String> updatedIds = new ArrayList<>();
        private int createCalls;
        private int failCreateAt;
        private int permanentCreateAt;

        @Override
        public Optional<ServiceNowConnector.RemoteMutationObject> findUnique(String identityField, String identity) {
            identityFields.add(identityField);
            identities.add(identity);
            return matches.removeFirst();
        }

        @Override
        public ServiceNowConnector.RemoteMutationObject create(Map<String, String> fields) {
            createCalls++;
            if (createCalls == failCreateAt) throw new ServiceNowUnavailableException("outage");
            if (createCalls == permanentCreateAt) throw new ServiceNowProtocolException("bad request");
            created.add(fields);
            return new ServiceNowConnector.RemoteMutationObject("0123456789abcdef0123456789abcde" + createCalls);
        }

        @Override
        public ServiceNowConnector.RemoteMutationObject update(String sysId, Map<String, String> fields) {
            updatedIds.add(sysId);
            updated.add(fields);
            return new ServiceNowConnector.RemoteMutationObject(sysId);
        }
    }
}
