package io.infranexum.adapters.jiraassets;

import io.infranexum.integrations.ConnectorOutboundPage;
import io.infranexum.integrations.ConnectorOutboundRecord;
import io.infranexum.integrations.ConnectorOutboundSource;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncBatchResult;
import io.infranexum.integrations.ConnectorSyncCompensationContext;
import io.infranexum.integrations.ConnectorSyncCompensationResult;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncHandler;
import io.infranexum.integrations.ConnectorKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Governed ITAM-to-Jira Assets upsert handler.
 *
 * <p>Retries are safe because every record is located by the immutable InfraNexum UUID before a
 * create. A transient failure never requests compensation: replay searches again and converges the
 * same remote object. Permanent failure after at least one successful write requires the configured
 * MANUAL rollback workflow.</p>
 */
public final class JiraAssetsSyncHandler implements ConnectorSyncHandler {
    private final JiraAssetsMutationSettings settings;
    private final ConnectorOutboundSource source;
    private final JiraAssetsMutationPort jira;

    public JiraAssetsSyncHandler(
            JiraAssetsMutationSettings settings, ConnectorOutboundSource source, JiraAssetsMutationPort jira) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
        this.jira = Objects.requireNonNull(jira, "jira");
    }

    @Override public ConnectorKey connectorKey() { return settings.connectorKey(); }

    @Override
    public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext context) {
        Objects.requireNonNull(context, "context");
        if (!connectorKey().equals(context.connectorKey()) || context.direction() != ConnectorSyncDirection.OUTBOUND) {
            return ConnectorSyncBatchResult.failed("JIRA_GOVERNANCE_MISMATCH", false, false);
        }
        Set<String> expectedFields = settings.attributeIds().keySet();
        if (!context.fields().equals(expectedFields) || context.propagateDeletions()) {
            return ConnectorSyncBatchResult.failed("JIRA_GOVERNANCE_MISMATCH", false, false);
        }

        final ConnectorOutboundPage page;
        try {
            page = Objects.requireNonNull(source.read(context), "outbound source page");
        } catch (RuntimeException unavailable) {
            return ConnectorSyncBatchResult.failed("LOCAL_SOURCE_UNAVAILABLE", true, false);
        }

        long processed = 0;
        long changed = 0;
        long rejected = 0;
        for (ConnectorOutboundRecord record : page.records()) {
            processed++;
            if (record.deleted()) {
                rejected++;
                continue;
            }
            try {
                Map<String, String> values = mappedValues(record);
                String identity = record.fields().get(settings.identitySourceField());
                Optional<JiraAssetsConnector.RemoteObject> existing = jira.findUnique(identityAql(identity));
                if (existing.isPresent()) {
                    jira.update(existing.orElseThrow().id(), settings.objectTypeId(), values);
                } else {
                    jira.create(settings.objectTypeId(), values);
                }
                changed++;
            } catch (JiraAssetsRateLimitedException | JiraAssetsUnavailableException transientFailure) {
                return ConnectorSyncBatchResult.failed("JIRA_RETRYABLE_FAILURE", true, false);
            } catch (JiraAssetsConnectorException permanentFailure) {
                return ConnectorSyncBatchResult.failed("JIRA_PERMANENT_FAILURE", false, changed > 0);
            } catch (IllegalArgumentException invalidRecord) {
                return ConnectorSyncBatchResult.failed("JIRA_MAPPING_INVALID", false, changed > 0);
            }
        }
        return ConnectorSyncBatchResult.applied(page.nextCursor(), processed, changed, rejected, page.completed());
    }

    @Override
    public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext context) {
        Objects.requireNonNull(context, "context");
        return ConnectorSyncCompensationResult.failed("MANUAL_COMPENSATION_REQUIRED");
    }

    private Map<String, String> mappedValues(ConnectorOutboundRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : settings.attributeIds().entrySet()) {
            String value = record.fields().get(mapping.getKey());
            if (value == null) throw new IllegalArgumentException("outbound record is missing governed field " + mapping.getKey());
            values.put(mapping.getValue(), value);
        }
        return Map.copyOf(values);
    }

    private String identityAql(String identity) {
        if (identity == null || !identity.matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("outbound Jira identity must be a canonical UUIDv7");
        }
        return "objectTypeId = \"" + settings.objectTypeId() + "\" AND \""
                + settings.identityAttributeName() + "\" = \"" + identity + "\"";
    }
}
