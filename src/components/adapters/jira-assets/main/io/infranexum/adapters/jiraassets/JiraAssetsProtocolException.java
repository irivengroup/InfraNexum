package io.infranexum.adapters.jiraassets;

/** Jira Assets returned an unsupported status or malformed/beyond-policy response. */
public final class JiraAssetsProtocolException extends JiraAssetsConnectorException {
    public JiraAssetsProtocolException(String message) { super(message); }
    public JiraAssetsProtocolException(String message, Throwable cause) { super(message, cause); }
}
