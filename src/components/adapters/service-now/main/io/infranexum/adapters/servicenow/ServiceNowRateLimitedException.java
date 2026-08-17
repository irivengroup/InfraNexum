package io.infranexum.adapters.servicenow;

/** ServiceNow rate limiting requires the operator to retry later; this slice never retries implicitly. */
public final class ServiceNowRateLimitedException extends ServiceNowConnectorException {
    public ServiceNowRateLimitedException() { super("ServiceNow rate limited the connector"); }
}
