package io.infranexum.adapters.servicenow;

/** ServiceNow rejected the externally supplied OAuth bearer token or its ACLs. */
public final class ServiceNowAuthenticationException extends ServiceNowConnectorException {
    public ServiceNowAuthenticationException() { super("ServiceNow authentication or authorization failed"); }
}
