package io.infranexum.adapters.servicenow;

/** Provider returned an unsupported or unsafe response. */
public final class ServiceNowProtocolException extends ServiceNowConnectorException {
    public ServiceNowProtocolException(String message) { super(message); }
    public ServiceNowProtocolException(String message, Throwable cause) { super(message, cause); }
}
