package io.infranexum.adapters.servicenow;

/** Provider or transport is unavailable without exposing remote response bodies. */
public final class ServiceNowUnavailableException extends ServiceNowConnectorException {
    public ServiceNowUnavailableException(String message) { super(message); }
    public ServiceNowUnavailableException(String message, Throwable cause) { super(message, cause); }
}
