package io.infranexum.adapters.jiraassets;

/** More than one Jira Assets object matched the governed InfraNexum identity. */
public final class JiraAssetsIdentityConflictException extends JiraAssetsConnectorException {
    private static final long serialVersionUID = 1L;
    public JiraAssetsIdentityConflictException() { super("Jira Assets identity mapping is not unique"); }
}
