package io.infranexum.identity.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.application.LocalAuthenticationPolicy;
import io.infranexum.identity.local.domain.LocalAccount;
import io.infranexum.identity.local.domain.LocalAccountStatus;
import io.infranexum.identity.local.domain.LocalPasswordPolicy;
import io.infranexum.identity.local.domain.LocalPasswordPolicyException;
import io.infranexum.identity.local.domain.LocalSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalIdentityValueObjectsTest {
    private static final Instant NOW = Instant.parse("2026-08-12T20:00:00Z");

    @Test
    void passwordPolicyAcceptsBoundaryAndRejectsEveryNormativeViolation() {
        LocalPasswordPolicy policy = new LocalPasswordPolicy();
        policy.validate("Abcdefghij1!".toCharArray());
        policy.validate(("A1!" + "x".repeat(125)).toCharArray());

        assertViolation(policy, "Aa1!", "min_length");
        assertViolation(policy, "abcdefghij1!", "uppercase");
        assertViolation(policy, "ABCDEFGHIJ1!", "lowercase");
        assertViolation(policy, "Abcdefghijkl!", "digit");
        assertViolation(policy, "Abcdefghijk1", "special");
        assertViolation(policy, "Abcdefghi1!\n", "control_character");
        assertViolation(policy, "A1!" + "x".repeat(126), "max_length");
        assertThrows(NullPointerException.class, () -> policy.validate(null));
    }

    @Test
    void passwordPolicyFailureDefensivelyCopiesViolationList() {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.add("uppercase");
        LocalPasswordPolicyException failure = new LocalPasswordPolicyException(values);
        values.add("digit");
        assertEquals(java.util.List.of("uppercase"), failure.violations());
        assertThrows(UnsupportedOperationException.class, () -> failure.violations().add("x"));
    }

    @Test
    void accountNormalizesUsernameAndEnforcesSecurityState() {
        LocalAccount account = account(" Admin.User ", LocalAccountStatus.ACTIVE, null, 0, 0);
        assertEquals("admin.user", account.username());
        assertFalse(account.lockedAt(NOW));
        assertFalse(account.lockedAt(NOW.plusSeconds(60)));
        LocalAccount locked = account("admin", LocalAccountStatus.ACTIVE, NOW.plusSeconds(30), 2, 3);
        assertTrue(locked.lockedAt(NOW));
        assertFalse(locked.lockedAt(NOW.plusSeconds(30)));

        for (String invalid : new String[] {"ab", "_admin", "Admin Space", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> LocalAccount.canonicalUsername(invalid), invalid);
        }
        assertThrows(NullPointerException.class, () -> LocalAccount.canonicalUsername(null));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(id(1), "admin", " ", "hash", false,
                LocalAccountStatus.ACTIVE, 0, null, 0, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(id(1), "admin", "Admin", " ", false,
                LocalAccountStatus.ACTIVE, 0, null, 0, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(id(1), "admin", "Admin", "hash", false,
                LocalAccountStatus.ACTIVE, -1, null, 0, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(id(1), "admin", "Admin", "hash", false,
                LocalAccountStatus.ACTIVE, 0, null, -1, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(id(1), "admin", "Admin", "hash", false,
                LocalAccountStatus.ACTIVE, 0, null, 0, -1, NOW, NOW));
    }

    @Test
    void sessionIsUsableOnlyWhenNotRevokedNotExpiredAndOnCurrentSecurityEpoch() {
        LocalSession session = session(null, 7, NOW.plusSeconds(300), NOW.plusSeconds(600));
        assertTrue(session.usableAt(NOW, 7));
        assertFalse(session.usableAt(NOW, 8));
        assertFalse(session.usableAt(NOW.plusSeconds(300), 7));
        assertFalse(session.usableAt(NOW.plusSeconds(600), 7));
        assertFalse(session(NOW.plusSeconds(1), 7, NOW.plusSeconds(300), NOW.plusSeconds(600)).usableAt(NOW, 7));

        assertThrows(IllegalArgumentException.class, () -> new LocalSession(
                id(10), id(11), "x", "0".repeat(64), 0, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(2), null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(
                id(10), id(11), "0".repeat(64), "A".repeat(64), 0, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(2), null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(
                id(10), id(11), "0".repeat(64), "1".repeat(64), -1, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(2), null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(
                id(10), id(11), "0".repeat(64), "1".repeat(64), 0, NOW, NOW, NOW.plusSeconds(1), NOW, null));
    }

    @Test
    void authenticationPolicyRejectsEveryUnsafeTimingConfiguration() {
        LocalAuthenticationPolicy valid = new LocalAuthenticationPolicy(
                5, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(12), Duration.ofMinutes(1));
        assertEquals(5, valid.lockThreshold());
        assertThrows(IllegalArgumentException.class, () -> policy(0, 1, 30, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(21, 1, 30, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(5, 0, 30, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(5, 1, 0, 60, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(5, 1, 60, 30, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(5, 1, 30, 60, -1));
        assertThrows(NullPointerException.class, () -> new LocalAuthenticationPolicy(5, null, Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new LocalAuthenticationPolicy(5, Duration.ofMinutes(1), null, Duration.ofMinutes(2), Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new LocalAuthenticationPolicy(5, Duration.ofMinutes(1), Duration.ofMinutes(1), null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new LocalAuthenticationPolicy(5, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(2), null));
    }

    private static LocalAuthenticationPolicy policy(int threshold, long lock, long idle, long absolute, long touch) {
        return new LocalAuthenticationPolicy(threshold, Duration.ofMinutes(lock), Duration.ofMinutes(idle),
                Duration.ofMinutes(absolute), Duration.ofMinutes(touch));
    }

    private static void assertViolation(LocalPasswordPolicy policy, String password, String violation) {
        LocalPasswordPolicyException failure = assertThrows(LocalPasswordPolicyException.class,
                () -> policy.validate(password.toCharArray()));
        assertTrue(failure.violations().contains(violation), failure.violations().toString());
    }

    private static LocalAccount account(String username, LocalAccountStatus status, Instant locked, long epoch, long version) {
        return new LocalAccount(id(1), username, "Administrator", "hash", false, status, 0, locked, epoch, version, NOW, NOW);
    }

    private static LocalSession session(Instant revoked, long epoch, Instant idle, Instant absolute) {
        return new LocalSession(id(10), id(11), "0".repeat(64), "1".repeat(64), epoch, NOW, NOW, idle, absolute, revoked);
    }

    private static DomainIdentifier id(int sequence) {
        return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(sequence)));
    }
}
