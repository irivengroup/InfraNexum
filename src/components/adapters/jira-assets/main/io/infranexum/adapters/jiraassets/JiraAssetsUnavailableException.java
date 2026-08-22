package io.infranexum.adapters.jiraassets;

/** Jira Assets could not be reached or returned a transient server failure. */
public final class JiraAssetsUnavailableException extends JiraAssetsConnectorException {
    private static final long serialVersionUID = 1L;
    public JiraAssetsUnavailableException(String message) { super(message); }
    public JiraAssetsUnavailableException(String message, Throwable cause) { super(message, cause); }
}
