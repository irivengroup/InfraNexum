package io.infranexum.server.identity;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalSession;
import java.time.Instant;
import java.util.UUID;

final class LocalAuthTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-12T20:00:00Z");
    static final String TOKEN = "bearer-token";
    static final String CSRF = "csrf-token";

    private LocalAuthTestFixtures() {}

    static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }

    static LocalAccount account(boolean mustChange) {
        return new LocalAccount(id(1), "admin", "Local Administrator", "$argon2id$fixture", mustChange,
                LocalAccountStatus.ACTIVE, 0, null, 0, 1, NOW.minusSeconds(60), NOW);
    }

    static LocalSession session() {
        return new LocalSession(id(2), id(1), "a".repeat(64), "b".repeat(64), 0,
                NOW, NOW, NOW.plusSeconds(1800), NOW.plusSeconds(43200), null);
    }

    static ValidatedSession validated(boolean mustChange) {
        return new ValidatedSession(account(mustChange), session());
    }

    static AuthenticatedSession authenticated(boolean mustChange) {
        return new AuthenticatedSession(account(mustChange), session(), TOKEN, CSRF);
    }

    static LocalAuthRuntimeProperties properties(boolean secure) {
        return new LocalAuthRuntimeProperties(true, secure ? "production" : "local", secure,
                "admin", "Local Administrator", "/run/secrets/admin", 5,
                null, null, null, null);
    }
}
