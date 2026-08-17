package io.infranexum.identity.local;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.local.domain.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Saturates independent validation predicates protecting local credential/session state. */
final class LocalIdentityBoundarySaturationTest {
    private static final DomainIdentifier ID = DomainIdentifier.parse("01900000-0000-7000-8000-000000000001");
    private static final Instant T = Instant.parse("2026-08-16T18:00:00Z");
    private static final String H = "a".repeat(64);

    @Test
    void accountValidatesEachIndependentScalarAndLockBranch() {
        LocalAccount account = new LocalAccount(ID, " Admin.User ", " Administrator ", H, false,
                LocalAccountStatus.ACTIVE, 0, T.plusSeconds(10), 0, 0, T, T);
        assertEquals("admin.user", account.username());
        assertTrue(account.lockedAt(T));
        assertFalse(account.lockedAt(T.plusSeconds(10)));
        assertFalse(new LocalAccount(ID,"abc","User",H,false,LocalAccountStatus.ACTIVE,0,null,0,0,T,T).lockedAt(T));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(ID,"abc","User",H,false,LocalAccountStatus.ACTIVE,-1,null,0,0,T,T));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(ID,"abc","User",H,false,LocalAccountStatus.ACTIVE,0,null,-1,0,T,T));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(ID,"abc","User",H,false,LocalAccountStatus.ACTIVE,0,null,0,-1,T,T));
        for (String value : List.of("ab", "a b", "a/xx", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> LocalAccount.canonicalUsername(value));
        }
        assertThrows(NullPointerException.class, () -> LocalAccount.canonicalUsername(null));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(ID,"abc"," ",H,false,LocalAccountStatus.ACTIVE,0,null,0,0,T,T));
        assertThrows(IllegalArgumentException.class, () -> new LocalAccount(ID,"abc","x".repeat(161),H,false,LocalAccountStatus.ACTIVE,0,null,0,0,T,T));
    }

    @Test
    void sessionUsabilityEvaluatesEveryFenceIndependently() {
        LocalSession session = new LocalSession(ID, ID, H, H, 7, T, T, T.plusSeconds(10), T.plusSeconds(20), null);
        assertTrue(session.usableAt(T.plusSeconds(1), 7));
        assertFalse(session.usableAt(T.plusSeconds(1), 8));
        assertFalse(session.usableAt(T.plusSeconds(10), 7));
        assertFalse(session.usableAt(T.plusSeconds(20), 7));
        LocalSession revoked = new LocalSession(ID,ID,H,H,7,T,T,T.plusSeconds(10),T.plusSeconds(20),T.plusSeconds(1));
        assertFalse(revoked.usableAt(T.plusSeconds(1),7));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(ID,ID,H,H,-1,T,T,T.plusSeconds(1),T.plusSeconds(2),null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(ID,ID,H,H,0,T,T,T.plusSeconds(1),T,null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(ID,ID,"A".repeat(64),H,0,T,T,T.plusSeconds(1),T.plusSeconds(2),null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSession(ID,ID,"a".repeat(63),H,0,T,T,T.plusSeconds(1),T.plusSeconds(2),null));
    }

    @Test
    void passwordPolicyExercisesAllCharacterCategoriesAndLengthFences() {
        LocalPasswordPolicy policy = new LocalPasswordPolicy();
        assertDoesNotThrow(() -> policy.validate("ValidPass1!x".toCharArray()));
        // Force the second operand of the lowercase/digit range checks false without weakening a valid password.
        assertDoesNotThrow(() -> policy.validate("ValidPass1!x{:".toCharArray()));
        for (String value : List.of("short1!A", "lowercase1!xx", "UPPERCASE1!XX", "NoDigitsHere!", "NoSpecial123A", "ValidPass1!\n")) {
            assertThrows(LocalPasswordPolicyException.class, () -> policy.validate(value.toCharArray()));
        }
        assertThrows(LocalPasswordPolicyException.class, () -> policy.validate(("Aa1!" + "x".repeat(125)).toCharArray()));
        assertThrows(NullPointerException.class, () -> policy.validate(null));
    }
}
