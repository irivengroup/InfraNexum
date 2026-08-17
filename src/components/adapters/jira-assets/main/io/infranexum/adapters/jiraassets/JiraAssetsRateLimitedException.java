package io.infranexum.adapters.jiraassets;

/** Jira Assets rejected the request due to provider-side rate limiting. */
public final class JiraAssetsRateLimitedException extends JiraAssetsConnectorException {
    public JiraAssetsRateLimitedException() { super("Jira Assets rate limit reached"); }
}
