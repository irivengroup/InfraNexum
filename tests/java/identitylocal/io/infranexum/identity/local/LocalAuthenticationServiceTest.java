package io.infranexum.identity.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.LocalAuthenticationPolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalAuthenticationException;
import io.infranexum.identity.local.domain.LocalPasswordPolicy;
import io.infranexum.identity.local.domain.LocalSession;
import io.infranexum.identity.local.domain.LocalSessionException;
import io.infranexum.identity.local.ports.LocalIdentityRepository;
import io.infranexum.identity.local.ports.LocalSessionRepository;
import io.infranexum.identity.local.ports.PasswordHasher;
import io.infranexum.identity.local.ports.SecureTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T20:00:00Z");
    private MemoryIdentities identities;
    private MemorySessions sessions;
    private FakeHasher hasher;
    private FakeTokens tokens;
    private MutableClock clock;
    private LocalAuthenticationService service;

    @BeforeEach
    void setUp() {
        identities = new MemoryIdentities();
        sessions = new MemorySessions();
        hasher = new FakeHasher();
        tokens = new FakeTokens();
        clock = new MutableClock(NOW);
        service = new LocalAuthenticationService(
                identities, sessions, hasher, tokens, new LocalPasswordPolicy(),
                new LocalAuthenticationPolicy(3, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofMinutes(1)),
                new UuidV7Generator(clock, new SecureRandom(new byte[] {1,2,3,4})), clock);
    }

    @Test
    void bootstrapCreatesSingleNormalizedAccountAndAlwaysWipesCallerPassword() {
        char[] password = "Bootstrap-Password!Aa1".toCharArray();
        LocalAccount account = service.bootstrap(" Admin ", "Local Administrator", password, true);
        assertEquals("admin", account.username());
        assertTrue(account.mustChange());
        assertTrue(allZero(password));
        assertTrue(identities.hasAnyAccount());

        char[] duplicate = "Another-Password!Aa1".toCharArray();
        assertThrows(IllegalStateException.class, () -> service.bootstrap("other", "Other", duplicate, true));
        assertTrue(allZero(duplicate));

        char[] weak = "weak".toCharArray();
        assertThrows(RuntimeException.class, () -> new LocalAuthenticationService(
                new MemoryIdentities(), new MemorySessions(), hasher, tokens, new LocalPasswordPolicy(),
                new LocalAuthenticationPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(3), Duration.ZERO),
                new UuidV7Generator(clock, new SecureRandom()), clock).bootstrap("admin", "Admin", weak, true));
        assertTrue(allZero(weak));
    }

    @Test
    void authenticationUsesGenericFailuresEquivalentWorkAndBoundedLockout() {
        bootstrap();
        char[] malformed = "Does-Not-Matter!Aa1".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.authenticate("!!", malformed));
        assertTrue(allZero(malformed));
        assertEquals(1, hasher.dummyWork);

        char[] unknown = "Does-Not-Matter!Aa1".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.authenticate("unknown", unknown));
        assertEquals(2, hasher.dummyWork);

        for (int attempt = 1; attempt <= 3; attempt++) {
            char[] wrong = "Wrong-Password!Aa1".toCharArray();
            assertThrows(LocalAuthenticationException.class, () -> service.authenticate("ADMIN", wrong));
            assertTrue(allZero(wrong));
        }
        LocalAccount locked = identities.findByUsername("admin").orElseThrow();
        assertTrue(locked.lockedAt(NOW));
        char[] correctButLocked = "Bootstrap-Password!Aa1".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.authenticate("admin", correctButLocked));
        assertEquals(3, hasher.dummyWork);

        clock.advance(Duration.ofMinutes(16));
        AuthenticatedSession accepted = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        assertEquals("admin", accepted.account().username());
        assertEquals(0, accepted.account().failedAttempts());
        assertEquals(null, accepted.account().lockedUntil());
    }

    @Test
    void suspendedAccountConsumesEquivalentWorkAndNeverCreatesSession() {
        LocalAccount account = bootstrap();
        identities.replace(new LocalAccount(account.id(), account.username(), account.displayName(), account.passwordHash(),
                account.mustChange(), LocalAccountStatus.SUSPENDED, 0, null, account.securityEpoch(), account.version(), account.createdAt(), NOW));
        assertThrows(LocalAuthenticationException.class,
                () -> service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray()));
        assertEquals(1, hasher.dummyWork);
        assertTrue(sessions.byHash.isEmpty());
    }

    @Test
    void successfulAuthenticationRehashesWhenRequiredAndCreatesOnlyHashedDurableSession() {
        LocalAccount account = bootstrap();
        identities.replace(new LocalAccount(account.id(), account.username(), account.displayName(), "old:Bootstrap-Password!Aa1",
                false, LocalAccountStatus.ACTIVE, 0, null, 0, 0, NOW, NOW));
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        assertTrue(authenticated.account().passwordHash().startsWith("new:"));
        assertFalse(authenticated.bearerToken().isBlank());
        assertFalse(authenticated.csrfToken().isBlank());
        assertNotEquals(authenticated.bearerToken(), authenticated.session().tokenHash());
        assertNotEquals(authenticated.csrfToken(), authenticated.session().csrfHash());
        assertEquals(tokens.sha256(authenticated.bearerToken()), authenticated.session().tokenHash());
    }

    @Test
    void credentialRotationRacesFailClosedAndCannotMintSessionFromStalePasswordState() {
        LocalAccount account = bootstrap();
        identities.rotateBeforeSuccessfulAuthentication = true;
        char[] current = "Bootstrap-Password!Aa1".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.authenticate("admin", current));
        assertTrue(allZero(current));
        assertTrue(sessions.byHash.isEmpty());
        assertEquals(account.securityEpoch() + 1, identities.findById(account.id()).orElseThrow().securityEpoch());

        identities.rotateBeforeSuccessfulAuthentication = false;
        identities.replace(account);
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        ValidatedSession validated = service.validate(authenticated.bearerToken());
        identities.rotateBeforePasswordChange = true;
        char[] old = "Bootstrap-Password!Aa1".toCharArray();
        char[] replacement = "Replacement-Password!Aa2".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.changePassword(validated, old, replacement));
        assertTrue(allZero(old));
        assertTrue(allZero(replacement));
    }

    @Test
    void validationTouchesIdleExpiryChecksCsrfAndLogoutRevokesSession() {
        bootstrap();
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        ValidatedSession first = service.validate(authenticated.bearerToken());
        assertEquals(authenticated.account().id(), first.account().id());
        service.verifyCsrf(first, authenticated.csrfToken());
        assertThrows(LocalSessionException.class, () -> service.verifyCsrf(first, null));
        assertThrows(LocalSessionException.class, () -> service.verifyCsrf(first, "wrong"));

        Instant originalSeen = first.session().lastSeenAt();
        clock.advance(Duration.ofMinutes(2));
        ValidatedSession touched = service.validate(authenticated.bearerToken());
        assertTrue(touched.session().lastSeenAt().isAfter(originalSeen));
        assertEquals(touched.session().lastSeenAt(), sessions.byHash.get(touched.session().tokenHash()).lastSeenAt());

        service.logout(touched);
        assertThrows(LocalSessionException.class, () -> service.validate(authenticated.bearerToken()));
    }

    @Test
    void validationRejectsMissingUnknownOrphanedSuspendedAndExpiredSessions() {
        assertThrows(LocalSessionException.class, () -> service.validate(null));
        assertThrows(LocalSessionException.class, () -> service.validate(" "));
        assertThrows(LocalSessionException.class, () -> service.validate("unknown"));

        LocalAccount account = bootstrap();
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        identities.byId.remove(account.id());
        assertThrows(LocalSessionException.class, () -> service.validate(authenticated.bearerToken()));

        identities.replace(account);
        identities.replace(new LocalAccount(account.id(), account.username(), account.displayName(), account.passwordHash(), false,
                LocalAccountStatus.SUSPENDED, 0, null, account.securityEpoch(), account.version(), NOW, NOW));
        assertThrows(LocalSessionException.class, () -> service.validate(authenticated.bearerToken()));

        identities.replace(account);
        clock.advance(Duration.ofHours(13));
        assertThrows(LocalSessionException.class, () -> service.validate(authenticated.bearerToken()));
    }

    @Test
    void passwordChangeRequiresCurrentCredentialRevokesPriorSessionsAndRotatesSecurityEpoch() {
        LocalAccount account = bootstrap();
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        ValidatedSession validated = service.validate(authenticated.bearerToken());

        char[] wrong = "Wrong-Password!Aa1".toCharArray();
        char[] candidate = "New-Secure-Password!Aa1".toCharArray();
        assertThrows(LocalAuthenticationException.class, () -> service.changePassword(validated, wrong, candidate));
        assertTrue(allZero(wrong)); assertTrue(allZero(candidate));

        char[] current = "Bootstrap-Password!Aa1".toCharArray();
        char[] weak = "weak".toCharArray();
        assertThrows(RuntimeException.class, () -> service.changePassword(validated, current, weak));
        assertTrue(allZero(current)); assertTrue(allZero(weak));

        char[] validCurrent = "Bootstrap-Password!Aa1".toCharArray();
        char[] next = "New-Secure-Password!Aa1".toCharArray();
        AuthenticatedSession replacement = service.changePassword(validated, validCurrent, next);
        assertTrue(allZero(validCurrent)); assertTrue(allZero(next));
        assertEquals(account.securityEpoch() + 1, replacement.account().securityEpoch());
        assertFalse(replacement.account().mustChange());
        assertThrows(LocalSessionException.class, () -> service.validate(authenticated.bearerToken()));
        assertEquals(replacement.account().id(), service.validate(replacement.bearerToken()).account().id());
    }

    @Test
    void zeroTouchIntervalDoesNotWriteSessionOnValidation() {
        bootstrap();
        service = new LocalAuthenticationService(identities, sessions, hasher, tokens, new LocalPasswordPolicy(),
                new LocalAuthenticationPolicy(3, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(12), Duration.ZERO),
                new UuidV7Generator(clock, new SecureRandom()), clock);
        AuthenticatedSession authenticated = service.authenticate("admin", "Bootstrap-Password!Aa1".toCharArray());
        int touches = sessions.touches;
        clock.advance(Duration.ofMinutes(5));
        service.validate(authenticated.bearerToken());
        assertEquals(touches, sessions.touches);
    }

    private LocalAccount bootstrap() {
        return service.bootstrap("admin", "Local Administrator", "Bootstrap-Password!Aa1".toCharArray(), true);
    }

    private static boolean allZero(char[] value) {
        for (char c : value) if (c != '\0') return false;
        return true;
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeHasher implements PasswordHasher {
        int dummyWork;
        @Override public String hash(char[] password) { return "new:" + new String(password); }
        @Override public boolean verify(char[] password, String encodedHash) {
            String expected = encodedHash.startsWith("old:") ? encodedHash.substring(4) : encodedHash.substring(4);
            return expected.equals(new String(password));
        }
        @Override public boolean needsRehash(String encodedHash) { return encodedHash.startsWith("old:"); }
        @Override public void consumeEquivalentWork(char[] password) { dummyWork++; }
    }

    private static final class FakeTokens implements SecureTokenGenerator {
        int sequence;
        @Override public String nextToken() { return "token-" + (++sequence) + "-abcdefghijklmnopqrstuvwxyz0123456789"; }
        @Override public String sha256(String token) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    private static final class MemoryIdentities implements LocalIdentityRepository {
        final Map<String, LocalAccount> byUsername = new HashMap<>();
        final Map<DomainIdentifier, LocalAccount> byId = new HashMap<>();
        boolean rotateBeforeSuccessfulAuthentication;
        boolean rotateBeforePasswordChange;
        @Override public boolean hasAnyAccount() { return !byId.isEmpty(); }
        @Override public Optional<LocalAccount> findByUsername(String username) { return Optional.ofNullable(byUsername.get(username)); }
        @Override public Optional<LocalAccount> findById(DomainIdentifier id) { return Optional.ofNullable(byId.get(id)); }
        @Override public void insert(LocalAccount account) { if (hasAnyAccount()) throw new IllegalStateException("duplicate"); replace(account); }
        void replace(LocalAccount account) { byUsername.put(account.username(), account); byId.put(account.id(), account); }
        @Override public LocalAccount recordFailedAuthentication(DomainIdentifier id, long expectedEpoch, int threshold, Duration duration, Instant now) {
            LocalAccount old = byId.get(id); if (old.securityEpoch() != expectedEpoch) throw new io.infranexum.identity.local.domain.LocalCredentialStateChangedException();
            int failures = old.failedAttempts() + 1;
            Instant locked = failures >= threshold ? now.plus(duration) : null; if (locked != null) failures = 0;
            LocalAccount updated = new LocalAccount(old.id(), old.username(), old.displayName(), old.passwordHash(), old.mustChange(), old.status(),
                    failures, locked, old.securityEpoch(), old.version() + 1, old.createdAt(), now); replace(updated); return updated;
        }
        @Override public LocalAccount recordSuccessfulAuthentication(DomainIdentifier id, long expectedEpoch, String replacementHash, Instant now) {
            LocalAccount old = byId.get(id);
            if (rotateBeforeSuccessfulAuthentication) {
                old = new LocalAccount(old.id(), old.username(), old.displayName(), old.passwordHash(), old.mustChange(), old.status(), old.failedAttempts(), old.lockedUntil(), old.securityEpoch() + 1, old.version() + 1, old.createdAt(), now);
                replace(old);
            }
            if (old.securityEpoch() != expectedEpoch) throw new io.infranexum.identity.local.domain.LocalCredentialStateChangedException();
            LocalAccount updated = new LocalAccount(old.id(), old.username(), old.displayName(),
                    replacementHash == null ? old.passwordHash() : replacementHash, old.mustChange(), old.status(), 0, null,
                    old.securityEpoch(), old.version() + 1, old.createdAt(), now); replace(updated); return updated;
        }
        @Override public LocalAccount changePassword(DomainIdentifier id, long expectedEpoch, String hash, boolean mustChange, Instant now) {
            LocalAccount old = byId.get(id);
            if (rotateBeforePasswordChange) {
                old = new LocalAccount(old.id(), old.username(), old.displayName(), old.passwordHash(), old.mustChange(), old.status(), old.failedAttempts(), old.lockedUntil(), old.securityEpoch() + 1, old.version() + 1, old.createdAt(), now);
                replace(old);
            }
            if (old.securityEpoch() != expectedEpoch) throw new io.infranexum.identity.local.domain.LocalCredentialStateChangedException();
            LocalAccount updated = new LocalAccount(old.id(), old.username(), old.displayName(), hash,
                    mustChange, old.status(), 0, null, old.securityEpoch() + 1, old.version() + 1, old.createdAt(), now); replace(updated); return updated;
        }
    }

    private static final class MemorySessions implements LocalSessionRepository {
        final Map<String, LocalSession> byHash = new HashMap<>();
        int touches;
        @Override public void insert(LocalSession session) { byHash.put(session.tokenHash(), session); }
        @Override public Optional<LocalSession> findByTokenHash(String hash) { return Optional.ofNullable(byHash.get(hash)); }
        @Override public void touch(DomainIdentifier id, Instant seen, Instant idle) {
            touches++;
            for (Map.Entry<String, LocalSession> entry : byHash.entrySet()) if (entry.getValue().id().equals(id)) {
                LocalSession old = entry.getValue(); entry.setValue(new LocalSession(old.id(), old.accountId(), old.tokenHash(), old.csrfHash(), old.securityEpoch(), old.createdAt(), seen, idle, old.absoluteExpiresAt(), old.revokedAt())); return;
            }
        }
        @Override public void revoke(DomainIdentifier id, Instant at) {
            for (Map.Entry<String, LocalSession> entry : byHash.entrySet()) if (entry.getValue().id().equals(id)) {
                LocalSession old = entry.getValue(); entry.setValue(new LocalSession(old.id(), old.accountId(), old.tokenHash(), old.csrfHash(), old.securityEpoch(), old.createdAt(), old.lastSeenAt(), old.idleExpiresAt(), old.absoluteExpiresAt(), at)); return;
            }
        }
        @Override public void revokeAllForAccount(DomainIdentifier accountId, Instant at) {
            for (LocalSession session : java.util.List.copyOf(byHash.values())) if (session.accountId().equals(accountId)) revoke(session.id(), at);
        }
    }
}
