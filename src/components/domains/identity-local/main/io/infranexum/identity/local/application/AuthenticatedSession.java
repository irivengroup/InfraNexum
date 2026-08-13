package io.infranexum.identity.local.application;

import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalSession;

/** Raw bearer/CSRF values exist only at the HTTP boundary; persistence contains hashes only. */
public record AuthenticatedSession(LocalAccount account, LocalSession session, String bearerToken, String csrfToken) {}
