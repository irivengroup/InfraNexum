package io.infranexum.adapters.servicenow;

/** More than one CMDB CI carries the immutable InfraNexum identity; mutation must stop fail-closed. */
public final class ServiceNowIdentityConflictException extends ServiceNowConnectorException {
    public ServiceNowIdentityConflictException() {
        super("ServiceNow identity is not unique");
    }
}
