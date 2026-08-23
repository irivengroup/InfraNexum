package io.infranexum.adapters.servicenow;

import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorOutboundPage;
import io.infranexum.integrations.ConnectorOutboundRecord;
import io.infranexum.integrations.ConnectorOutboundSource;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncBatchResult;
import io.infranexum.integrations.ConnectorSyncCompensationContext;
import io.infranexum.integrations.ConnectorSyncCompensationResult;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Governed ITAM-to-ServiceNow CMDB upsert handler.
 *
 * <p>Every retry first searches the immutable InfraNexum UUID stored in the configured custom
 * ServiceNow column. Transient outcomes therefore remain replayable without claiming exactly-once
 * provider execution. Permanent failures after a prior write require the configured MANUAL
 * compensation workflow; remote deletion is deliberately unsupported in this phase.</p>
 */
public final class ServiceNowSyncHandler implements ConnectorSyncHandler {
    private final ServiceNowMutationSettings settings;
    private final ConnectorOutboundSource source;
    private final ServiceNowMutationPort serviceNow;

    public ServiceNowSyncHandler(
            ServiceNowMutationSettings settings,
            ConnectorOutboundSource source,
            ServiceNowMutationPort serviceNow) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
        this.serviceNow = Objects.requireNonNull(serviceNow, "serviceNow");
    }

    @Override public ConnectorKey connectorKey() { return settings.connectorKey(); }

    @Override
    public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext context) {
        Objects.requireNonNull(context, "context");
        if (!connectorKey().equals(context.connectorKey()) || context.direction() != ConnectorSyncDirection.OUTBOUND) {
            return ConnectorSyncBatchResult.failed("SERVICE_NOW_GOVERNANCE_MISMATCH", false, false);
        }
        Set<String> expectedFields = settings.fieldNames().keySet();
        if (!context.fields().equals(expectedFields) || context.propagateDeletions()) {
            return ConnectorSyncBatchResult.failed("SERVICE_NOW_GOVERNANCE_MISMATCH", false, false);
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
                Map<String, String> fields = mappedValues(record);
                String identity = record.fields().get(settings.identitySourceField());
                Optional<ServiceNowConnector.RemoteMutationObject> existing =
                        serviceNow.findUnique(settings.identityField(), identity);
                if (existing.isPresent()) {
                    serviceNow.update(existing.orElseThrow().sysId(), fields);
                } else {
                    serviceNow.create(fields);
                }
                changed++;
            } catch (ServiceNowRateLimitedException | ServiceNowUnavailableException transientFailure) {
                return ConnectorSyncBatchResult.failed("SERVICE_NOW_RETRYABLE_FAILURE", true, false);
            } catch (ServiceNowConnectorException permanentFailure) {
                return ConnectorSyncBatchResult.failed("SERVICE_NOW_PERMANENT_FAILURE", false, changed > 0);
            } catch (IllegalArgumentException invalidRecord) {
                return ConnectorSyncBatchResult.failed("SERVICE_NOW_MAPPING_INVALID", false, changed > 0);
            }
        }
        return ConnectorSyncBatchResult.applied(
                page.nextCursor(), processed, changed, rejected, page.completed());
    }

    @Override
    public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext context) {
        Objects.requireNonNull(context, "context");
        return ConnectorSyncCompensationResult.failed("MANUAL_COMPENSATION_REQUIRED");
    }

    private Map<String, String> mappedValues(ConnectorOutboundRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : settings.fieldNames().entrySet()) {
            String value = record.fields().get(mapping.getKey());
            if (value == null) {
                throw new IllegalArgumentException(
                        "outbound record is missing governed field " + mapping.getKey());
            }
            values.put(mapping.getValue(), value);
        }
        return Map.copyOf(values);
    }
}
