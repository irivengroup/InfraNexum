package io.infranexum.adapters.jiraassets;

/** Provider rejected the configured Jira Assets bearer credential or its required read scopes. */
public final class JiraAssetsAuthenticationException extends JiraAssetsConnectorException {
    public JiraAssetsAuthenticationException() { super("Jira Assets authentication or authorization failed"); }
}
