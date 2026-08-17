package io.infranexum.adapters.jiraassets;

/** Base failure for Jira Assets provider interactions; messages never contain provider payloads or secrets. */
public class JiraAssetsConnectorException extends RuntimeException {
    public JiraAssetsConnectorException(String message) { super(message); }
    public JiraAssetsConnectorException(String message, Throwable cause) { super(message, cause); }
}
