package io.infranexum.identity.local.application;

import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalSession;

public record ValidatedSession(LocalAccount account, LocalSession session) {}
