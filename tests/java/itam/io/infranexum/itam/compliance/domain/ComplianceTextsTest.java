package io.infranexum.itam.compliance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Covers shared contractual text guards so every caller inherits the same fail-closed semantics. */
class ComplianceTextsTest {
    @Test
    void requiredTextRejectsNullControlsAndBothLengthBoundariesBeforeReturningNormalizedValue() {
        assertThrows(NullPointerException.class, () -> ComplianceTexts.text(null, "contract", 2, 4));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTexts.text("a\nb", "contract", 2, 4));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTexts.text(" a ", "contract", 2, 4));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTexts.text("abcde", "contract", 2, 4));
        assertEquals("abc", ComplianceTexts.text(" abc ", "contract", 2, 4));
    }

    @Test
    void optionalTextDistinguishesNullBlankInvalidAndPresentValues() {
        assertNull(ComplianceTexts.optional(null, "proof", 8));
        assertNull(ComplianceTexts.optional("   ", "proof", 8));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTexts.optional("a\tb", "proof", 8));
        assertThrows(IllegalArgumentException.class, () -> ComplianceTexts.optional("123456789", "proof", 8));
        assertEquals("proof", ComplianceTexts.optional(" proof ", "proof", 8));
    }
}
