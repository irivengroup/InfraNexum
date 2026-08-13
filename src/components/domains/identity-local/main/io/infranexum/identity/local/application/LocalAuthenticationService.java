package io.infranexum.identity.local.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalAuthenticationException;
import io.infranexum.identity.local.domain.LocalCredentialStateChangedException;
import io.infranexum.identity.local.domain.LocalPasswordPolicy;
import io.infranexum.identity.local.domain.LocalSession;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.identity.local.ports.LocalIdentityRepository;
import io.infranexum.identity.local.ports.LocalSessionRepository;
import io.infranexum.identity.local.ports.PasswordHasher;
import io.infranexum.identity.local.ports.SecureTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Local authentication application service with opaque durable sessions and fail-closed security state. */
public final class LocalAuthenticationService {
    private final LocalIdentityRepository identities;
    private final LocalSessionRepository sessions;
    private final PasswordHasher hasher;
    private final SecureTokenGenerator tokens;
    private final LocalPasswordPolicy passwordPolicy;
    private final LocalAuthenticationPolicy policy;
    private final UuidV7Generator ids;
    private final Clock clock;

    public LocalAuthenticationService(
            LocalIdentityRepository identities,
            LocalSessionRepository sessions,
            PasswordHasher hasher,
            SecureTokenGenerator tokens,
            LocalPasswordPolicy passwordPolicy,
            LocalAuthenticationPolicy policy,
            UuidV7Generator ids,
            Clock clock) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalAccount bootstrap(String username, String displayName, char[] password, boolean mustChange) {
        Objects.requireNonNull(password, "password");
        try {
            passwordPolicy.validate(password);
            if (identities.hasAnyAccount()) throw new IllegalStateException("local identity bootstrap is already complete");
            Instant now = clock.instant();
            LocalAccount account = new LocalAccount(
                    ids.next(), username, displayName, hasher.hash(password), mustChange,
                    LocalAccountStatus.ACTIVE, 0, null, 0, 0, now, now);
            identities.insert(account);
            return account;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public AuthenticatedSession authenticate(String username, char[] password) {
        Objects.requireNonNull(password, "password");
        Instant now = clock.instant();
        try {
            String canonical;
            try {
                canonical = LocalAccount.canonicalUsername(username);
            } catch (RuntimeException invalid) {
                hasher.consumeEquivalentWork(password);
                throw new LocalAuthenticationException();
            }
            LocalAccount account = identities.findByUsername(canonical).orElse(null);
            if (account == null) {
                hasher.consumeEquivalentWork(password);
                throw new LocalAuthenticationException();
            }
            if (account.status() != LocalAccountStatus.ACTIVE || account.lockedAt(now)) {
                hasher.consumeEquivalentWork(password);
                throw new LocalAuthenticationException();
            }
            if (!hasher.verify(password, account.passwordHash())) {
                try {
                    identities.recordFailedAuthentication(
                            account.id(), account.securityEpoch(), policy.lockThreshold(), policy.lockDuration(), now);
                } catch (LocalCredentialStateChangedException concurrentRotation) {
                    throw new LocalAuthenticationException();
                }
                throw new LocalAuthenticationException();
            }
            String replacement = hasher.needsRehash(account.passwordHash()) ? hasher.hash(password) : null;
            try {
                account = identities.recordSuccessfulAuthentication(account.id(), account.securityEpoch(), replacement, now);
            } catch (LocalCredentialStateChangedException concurrentRotation) {
                throw new LocalAuthenticationException();
            }
            return createSession(account, now);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public ValidatedSession validate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) throw new LocalSessionException("session is required");
        Instant now = clock.instant();
        String tokenHash = tokens.sha256(bearerToken);
        LocalSession session = sessions.findByTokenHash(tokenHash)
                .orElseThrow(() -> new LocalSessionException("session is invalid"));
        LocalAccount account = identities.findById(session.accountId())
                .orElseThrow(() -> new LocalSessionException("session is invalid"));
        if (account.status() != LocalAccountStatus.ACTIVE || !session.usableAt(now, account.securityEpoch())) {
            throw new LocalSessionException("session is invalid");
        }
        if (!policy.touchInterval().isZero() && !now.isBefore(session.lastSeenAt().plus(policy.touchInterval()))) {
            Instant idleExpiry = min(now.plus(policy.idleTimeout()), session.absoluteExpiresAt());
            sessions.touch(session.id(), now, idleExpiry);
            session = new LocalSession(session.id(), session.accountId(), session.tokenHash(), session.csrfHash(),
                    session.securityEpoch(), session.createdAt(), now, idleExpiry, session.absoluteExpiresAt(), session.revokedAt());
        }
        return new ValidatedSession(account, session);
    }

    public void verifyCsrf(ValidatedSession validated, String csrfToken) {
        Objects.requireNonNull(validated, "validated");
        if (csrfToken == null || csrfToken.isBlank()) throw new LocalSessionException("CSRF validation failed");
        byte[] actual = tokens.sha256(csrfToken).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = validated.session().csrfHash().getBytes(StandardCharsets.US_ASCII);
        try {
            if (!MessageDigest.isEqual(actual, expected)) throw new LocalSessionException("CSRF validation failed");
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    public void logout(ValidatedSession validated) {
        sessions.revoke(validated.session().id(), clock.instant());
    }

    public AuthenticatedSession changePassword(ValidatedSession validated, char[] currentPassword, char[] newPassword) {
        Objects.requireNonNull(validated, "validated");
        Objects.requireNonNull(currentPassword, "currentPassword");
        Objects.requireNonNull(newPassword, "newPassword");
        try {
            passwordPolicy.validate(newPassword);
            LocalAccount account = validated.account();
            if (!hasher.verify(currentPassword, account.passwordHash())) throw new LocalAuthenticationException();
            Instant now = clock.instant();
            LocalAccount changed;
            try {
                changed = identities.changePassword(
                        account.id(), account.securityEpoch(), hasher.hash(newPassword), false, now);
            } catch (LocalCredentialStateChangedException concurrentRotation) {
                throw new LocalAuthenticationException();
            }
            sessions.revokeAllForAccount(account.id(), now);
            return createSession(changed, now);
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private AuthenticatedSession createSession(LocalAccount account, Instant now) {
        String bearer = tokens.nextToken();
        String csrf = tokens.nextToken();
        Instant absolute = now.plus(policy.absoluteTimeout());
        LocalSession session = new LocalSession(
                ids.next(), account.id(), tokens.sha256(bearer), tokens.sha256(csrf), account.securityEpoch(),
                now, now, min(now.plus(policy.idleTimeout()), absolute), absolute, null);
        sessions.insert(session);
        return new AuthenticatedSession(account, session, bearer, csrf);
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
