package io.infranexum.adapters.servicenow;

/** Base failure for safe provider-specific error translation at the Server boundary. */
public sealed class ServiceNowConnectorException extends RuntimeException
        permits ServiceNowAuthenticationException, ServiceNowRateLimitedException,
                ServiceNowUnavailableException, ServiceNowProtocolException, ServiceNowIdentityConflictException {
    ServiceNowConnectorException(String message) { super(message); }
    ServiceNowConnectorException(String message, Throwable cause) { super(message, cause); }
}
