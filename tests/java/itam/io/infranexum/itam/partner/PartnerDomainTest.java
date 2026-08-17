package io.infranexum.itam.partner;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerPage;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerCode;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerContact;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import io.infranexum.itam.partner.domain.PartnerNotFoundException;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
import io.infranexum.itam.partner.domain.PartnerRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exhaustive invariant tests for the immutable ITAM Partner aggregate and value objects. */
final class PartnerDomainTest {
    private static final DomainIdentifier ID = DomainIdentifier.parse("01900000-0000-7000-8000-000000000001");
    private static final DomainIdentifier ORG = DomainIdentifier.parse("01900000-0000-7000-8000-000000000002");
    private static final DomainIdentifier SUB = DomainIdentifier.parse("01900000-0000-7000-8000-000000000003");
    private static final DomainIdentifier ACTOR = DomainIdentifier.parse("01900000-0000-7000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-14T20:00:00Z");

    @Test
    void roleAndStatusWireContractsAreExactAndRejectUnknownValues() {
        assertEquals(PartnerRole.MANUFACTURER, PartnerRole.parse(" MANUFACTURER "));
        assertEquals("third_party_support_provider", PartnerRole.THIRD_PARTY_SUPPORT_PROVIDER.wireValue());
        assertThrows(IllegalArgumentException.class, () -> PartnerRole.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PartnerRole.parse("unknown"));
        assertEquals(PartnerAuthorizationStatus.PENDING_APPROVAL, PartnerAuthorizationStatus.parse("pending_approval"));
        assertThrows(IllegalArgumentException.class, () -> PartnerAuthorizationStatus.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PartnerAuthorizationStatus.parse("unknown"));

        assertTrue(PartnerAuthorizationStatus.DRAFT.canTransitionTo(PartnerAuthorizationStatus.PENDING_APPROVAL));
        assertFalse(PartnerAuthorizationStatus.DRAFT.canTransitionTo(PartnerAuthorizationStatus.ACTIVE));
        assertTrue(PartnerAuthorizationStatus.PENDING_APPROVAL.canTransitionTo(PartnerAuthorizationStatus.ACTIVE));
        assertTrue(PartnerAuthorizationStatus.PENDING_APPROVAL.canTransitionTo(PartnerAuthorizationStatus.DRAFT));
        assertTrue(PartnerAuthorizationStatus.ACTIVE.canTransitionTo(PartnerAuthorizationStatus.SUSPENDED));
        assertTrue(PartnerAuthorizationStatus.ACTIVE.canTransitionTo(PartnerAuthorizationStatus.RETIRED));
        assertTrue(PartnerAuthorizationStatus.SUSPENDED.canTransitionTo(PartnerAuthorizationStatus.ACTIVE));
        assertTrue(PartnerAuthorizationStatus.SUSPENDED.canTransitionTo(PartnerAuthorizationStatus.RETIRED));
        assertFalse(PartnerAuthorizationStatus.RETIRED.canTransitionTo(PartnerAuthorizationStatus.ACTIVE));

        for (PartnerAuthorizationStatus source : PartnerAuthorizationStatus.values()) {
            for (PartnerAuthorizationStatus target : PartnerAuthorizationStatus.values()) {
                boolean expected = switch (source) {
                    case DRAFT -> target == PartnerAuthorizationStatus.PENDING_APPROVAL;
                    case PENDING_APPROVAL -> target == PartnerAuthorizationStatus.ACTIVE || target == PartnerAuthorizationStatus.DRAFT;
                    case ACTIVE -> target == PartnerAuthorizationStatus.SUSPENDED || target == PartnerAuthorizationStatus.RETIRED;
                    case SUSPENDED -> target == PartnerAuthorizationStatus.ACTIVE || target == PartnerAuthorizationStatus.RETIRED;
                    case RETIRED -> false;
                };
                assertEquals(expected, source.canTransitionTo(target));
            }
            assertFalse(source.canTransitionTo(null));
        }
    }

    @Test
    void partnerCodeNormalizesAndOrdersButRejectsInvalidValues() {
        assertEquals("ABC-001", new PartnerCode(" abc-001 ").value());
        assertTrue(new PartnerCode("ABC-001").compareTo(new PartnerCode("ABC-002")) < 0);
        assertEquals("ABC-001", new PartnerCode("ABC-001").toString());
        assertThrows(NullPointerException.class, () -> new PartnerCode(null));
        for (String invalid : List.of("A", "_BAD", "AB_1", "A".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> new PartnerCode(invalid));
        }
        assertThrows(NullPointerException.class, () -> new PartnerCode("ABC").compareTo(null));
    }

    @Test
    void structuredExternalIdentifiersAccreditationsAndContactsValidateAllBoundaries() {
        PartnerExternalId external = new PartnerExternalId(" DUNS ", " ab-12 ");
        assertEquals("duns", external.authority());
        assertEquals("duns:AB-12", external.identityToken());
        for (String authority : List.of("A", "1bad", "bad space")) {
            assertThrows(IllegalArgumentException.class, () -> new PartnerExternalId(authority, "x"));
        }
        assertThrows(NullPointerException.class, () -> new PartnerExternalId(null, "x"));
        assertThrows(NullPointerException.class, () -> new PartnerExternalId("duns", null));
        assertThrows(IllegalArgumentException.class, () -> new PartnerExternalId("duns", ""));
        assertThrows(IllegalArgumentException.class, () -> new PartnerExternalId("duns", "x".repeat(241)));
        assertThrows(IllegalArgumentException.class, () -> new PartnerExternalId("duns", "x\n"));

        PartnerAccreditation accreditation = new PartnerAccreditation(
                "ISO27001", "ISO", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "evidence-1");
        assertEquals("ISO27001", accreditation.code());
        assertThrows(NullPointerException.class, () -> new PartnerAccreditation("ISO", "Issuer", null, null, "evidence"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerAccreditation(
                "ISO", "Issuer", LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1), "evidence"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerAccreditation("I", "Issuer", LocalDate.now(), null, "evidence"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerAccreditation("ISO", "I", LocalDate.now(), null, "evidence"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerAccreditation("ISO", "Issuer", LocalDate.now(), null, "x"));

        assertEquals("support", new PartnerContact(" SUPPORT ", "Help desk", "help@example.test", null, "https://support.example.test").type());
        assertEquals("+33123456789", new PartnerContact("phone", "Hotline", null, "+33123456789", null).phone());
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("!", "Help", "help@example.test", null, null));
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("support", "Help", "invalid", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PartnerContact("support", "Support Team", null, null, "/relative-support"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("support", "Help", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("support", "Help", null, null, "ftp://example.test"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("support", "Help", null, null, "https://user:pass@example.test"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerContact("support", "\nHelp", "help@example.test", null, null));
    }

    @Test
    void partnerWebsiteRejectsRelativeUrisWithoutAnHttpScheme() {
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(ID, ORG, SUB, new PartnerCode("ABC"),
                "Legal Name", "Display Name", "FR", Set.of(PartnerRole.MANUFACTURER), PartnerAuthorizationStatus.DRAFT,
                LocalDate.of(2026, 1, 1), null, "/relative", null, List.of(), List.of(), List.of(), List.of(),
                1, NOW, NOW, ACTOR, ACTOR, "reason"));
    }

    @Test
    void draftNormalizesIdentityAndLifecyclePreservesImmutableData() {
        Partner draft = draft();
        assertAll(
                () -> assertEquals(ID, draft.id()),
                () -> assertEquals(ORG, draft.governingOrganizationId()),
                () -> assertEquals(SUB, draft.governingSubdivisionId()),
                () -> assertEquals("ACME-001", draft.code().value()),
                () -> assertEquals("Acme Infrastructure S.A.S.", draft.legalName()),
                () -> assertEquals("Acme", draft.displayName()),
                () -> assertEquals("FR", draft.countryCode()),
                () -> assertEquals(PartnerAuthorizationStatus.DRAFT, draft.authorizationStatus()),
                () -> assertEquals(1, draft.version()),
                () -> assertEquals(NOW, draft.createdAt()),
                () -> assertEquals(NOW, draft.updatedAt()),
                () -> assertEquals(ACTOR, draft.createdBy()),
                () -> assertEquals(ACTOR, draft.updatedBy()),
                () -> assertEquals("Initial registration", draft.lastReason()),
                () -> assertEquals("ACME INFRASTRUCTURE S A S", draft.normalizedLegalName()),
                () -> assertTrue(draft.identityTokens().contains("name:FR:ACME INFRASTRUCTURE S A S")),
                () -> assertTrue(draft.identityTokens().contains("alias:FR:ACME FRANCE")),
                () -> assertTrue(draft.identityTokens().contains("external:duns:ACME-EXT")),
                () -> assertFalse(draft.selectableOn(LocalDate.of(2026, 8, 14))));

        Partner pending = draft.submitApproval(ACTOR, "Review complete", NOW.plusSeconds(1));
        Partner active = pending.authorize(ACTOR, "Approved", NOW.plusSeconds(2), LocalDate.of(2026, 8, 14));
        Partner suspended = active.suspend(ACTOR, "Incident", NOW.plusSeconds(3));
        Partner reactivated = suspended.reactivate(ACTOR, "Remediated", NOW.plusSeconds(4), LocalDate.of(2026, 8, 14));
        Partner retired = reactivated.retire(ACTOR, "End of relationship", NOW.plusSeconds(5));
        assertEquals(6, retired.version());
        assertEquals(PartnerAuthorizationStatus.RETIRED, retired.authorizationStatus());
        assertTrue(active.selectableOn(LocalDate.of(2026, 1, 1)));
        assertTrue(active.selectableOn(LocalDate.of(2030, 12, 31)));
        assertFalse(active.selectableOn(LocalDate.of(2025, 12, 31)));
        assertFalse(active.selectableOn(LocalDate.of(2031, 1, 1)));
        assertThrows(PartnerConflictException.class, () -> retired.submitApproval(ACTOR, "invalid", NOW.plusSeconds(6)));
        assertThrows(IllegalArgumentException.class, () -> active.suspend(ACTOR, "bad time", NOW.minusSeconds(1)));
    }

    @Test
    void lifecycleRejectsAuthorizationOutsideValidityAndIllegalTransitions() {
        Partner pending = draft().submitApproval(ACTOR, "Review complete", NOW.plusSeconds(1));
        PartnerConflictException before = assertThrows(PartnerConflictException.class,
                () -> pending.authorize(ACTOR, "Approved", NOW.plusSeconds(2), LocalDate.of(2025, 1, 1)));
        assertEquals("PARTNER_AUTHORIZATION_PERIOD_INVALID", before.code());
        PartnerConflictException after = assertThrows(PartnerConflictException.class,
                () -> pending.authorize(ACTOR, "Approved", NOW.plusSeconds(2), LocalDate.of(2031, 1, 1)));
        assertEquals("PARTNER_AUTHORIZATION_PERIOD_INVALID", after.code());
        Partner active = pending.authorize(ACTOR, "Approved", NOW.plusSeconds(2), LocalDate.of(2026, 8, 14));
        assertThrows(PartnerConflictException.class, () -> active.authorize(ACTOR, "again", NOW.plusSeconds(3), LocalDate.of(2026, 8, 14)));
        Partner suspended = active.suspend(ACTOR, "incident", NOW.plusSeconds(3));
        assertThrows(PartnerConflictException.class,
                () -> suspended.reactivate(ACTOR, "invalid period", NOW.plusSeconds(4), LocalDate.of(2031, 1, 1)));
    }

    @Test
    void restoreAndAggregateConstructorRejectMalformedPersistedState() {
        Partner restored = Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "DE",
                Set.of(PartnerRole.SUPPLIER), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(),
                9, NOW, NOW.plusSeconds(2), ACTOR, ACTOR, "restored");
        assertEquals(9, restored.version());
        assertTrue(restored.selectableOn(LocalDate.of(2035, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "DE",
                Set.of(PartnerRole.SUPPLIER), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1), null, null, List.of(), List.of(), List.of(), List.of(),
                1, NOW, NOW, ACTOR, ACTOR, "restored"));
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "DE",
                Set.of(PartnerRole.SUPPLIER), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(),
                0, NOW, NOW, ACTOR, ACTOR, "restored"));
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "DE",
                Set.of(PartnerRole.SUPPLIER), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(),
                1, NOW, NOW.minusSeconds(1), ACTOR, ACTOR, "restored"));
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "ZZ",
                Set.of(PartnerRole.SUPPLIER), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(),
                1, NOW, NOW, ACTOR, ACTOR, "restored"));
        assertThrows(IllegalArgumentException.class, () -> Partner.restore(
                ID, ORG, null, new PartnerCode("REST-001"), "Restored SAS", "Restored", "FR",
                Set.of(), PartnerAuthorizationStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(),
                1, NOW, NOW, ACTOR, ACTOR, "restored"));
    }

    @Test
    void aggregateRejectsUnsafeUrlsAliasesTextAndOversizedCollections() {
        assertThrows(IllegalArgumentException.class, () -> draftWithUrls("ftp://example.test", null));
        assertThrows(IllegalArgumentException.class, () -> draftWithUrls("https://u:p@example.test", null));
        assertThrows(IllegalArgumentException.class, () -> draftWithUrls("x".repeat(2049), null));
        assertThrows(IllegalArgumentException.class, () -> draftWithUrls(null, "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> Partner.draft(
                ID, ORG, SUB, new PartnerCode("BAD-001"), "A", "Display", "FR", Set.of(PartnerRole.SUPPLIER),
                LocalDate.of(2026, 1, 1), null, null, null, List.of(), List.of(), List.of(), List.of(), ACTOR, "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> Partner.draft(
                ID, ORG, SUB, new PartnerCode("BAD-001"), "Legal name", "Display", "FR", Set.of(PartnerRole.SUPPLIER),
                LocalDate.of(2026, 1, 1), null, null, null, List.of("x"), List.of(), List.of(), List.of(), ACTOR, "reason", NOW));
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < 65; i++) aliases.add("Alias " + i);
        assertThrows(IllegalArgumentException.class, () -> Partner.draft(
                ID, ORG, SUB, new PartnerCode("BAD-001"), "Legal name", "Display", "FR", Set.of(PartnerRole.SUPPLIER),
                LocalDate.of(2026, 1, 1), null, null, null, aliases, List.of(), List.of(), List.of(), ACTOR, "reason", NOW));
    }

    @Test
    void commandContextSearchAndPageValidateBoundaries() {
        PartnerCommandContext context = new PartnerCommandContext(ACTOR, ID, " command-0001 ", " reason ");
        assertEquals("command-0001", context.idempotencyKey());
        assertEquals("reason", context.reason());
        assertThrows(NullPointerException.class, () -> new PartnerCommandContext(null, ID, "command-0001", "reason"));
        assertThrows(NullPointerException.class, () -> new PartnerCommandContext(ACTOR, null, "command-0001", "reason"));
        assertThrows(NullPointerException.class, () -> new PartnerCommandContext(ACTOR, ID, null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerCommandContext(ACTOR, ID, "short", "reason"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerCommandContext(ACTOR, ID, "x".repeat(201), "reason"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerCommandContext(ACTOR, ID, "command-0001\n", "reason"));
        assertThrows(NullPointerException.class, () -> new PartnerCommandContext(ACTOR, ID, "command-0001", null));
        assertThrows(IllegalArgumentException.class, () -> new PartnerCommandContext(ACTOR, ID, "command-0001", "x"));
        assertThrows(IllegalArgumentException.class, () -> new PartnerCommandContext(ACTOR, ID, "command-0001", "x".repeat(1025)));

        PartnerSearchCriteria criteria = new PartnerSearchCriteria(ORG, PartnerRole.SUPPLIER, PartnerAuthorizationStatus.ACTIVE, " fr ", " ISO27001 ", LocalDate.now(), ID, 200);
        assertEquals("FR", criteria.countryCode());
        assertEquals("ISO27001", criteria.accreditation());
        assertThrows(IllegalArgumentException.class, () -> new PartnerSearchCriteria(null, null, null, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new PartnerSearchCriteria(null, null, null, null, null, null, null, 201));
        assertThrows(IllegalArgumentException.class, () -> new PartnerSearchCriteria(null, null, null, "FRA", null, null, null, 1));
        assertNull(new PartnerSearchCriteria(null, null, null, " ", " ", null, null, 1).countryCode());

        List<Partner> mutable = new ArrayList<>(List.of(draft()));
        PartnerPage page = new PartnerPage(mutable, ID);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertEquals(ID, page.nextCursor());
        assertThrows(NullPointerException.class, () -> new PartnerPage(null, null));
    }

    @Test
    void typedExceptionsPreserveStableMessagesAndCodes() {
        PartnerConflictException conflict = new PartnerConflictException("CODE", "detail");
        assertEquals("CODE", conflict.code());
        assertEquals("detail", conflict.getMessage());
        assertEquals("partner not found", new PartnerNotFoundException().getMessage());
        assertEquals("itam.partners.max quota exceeded", new PartnerQuotaException().getMessage());
    }

    private static Partner draft() {
        return Partner.draft(
                ID, ORG, SUB, new PartnerCode("acme-001"), "Acme Infrastructure S.A.S.", "Acme", "fr",
                Set.of(PartnerRole.MANUFACTURER, PartnerRole.THIRD_PARTY_SUPPORT_PROVIDER),
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 12, 31), "https://www.example.test", "https://support.example.test",
                List.of("Acme France"), List.of(new PartnerExternalId("duns", "acme-ext")),
                List.of(new PartnerAccreditation("ISO27001", "ISO", LocalDate.of(2026, 1, 1), null, "certificate-1")),
                List.of(new PartnerContact("support", "Support", "support@example.test", null, null)),
                ACTOR, "Initial registration", NOW);
    }

    private static Partner draftWithUrls(String official, String support) {
        return Partner.draft(
                ID, ORG, SUB, new PartnerCode("URL-001"), "URL Partner SAS", "URL Partner", "FR",
                Set.of(PartnerRole.SUPPLIER), LocalDate.of(2026, 1, 1), null, official, support,
                List.of(), List.of(), List.of(), List.of(), ACTOR, "Initial registration", NOW);
    }
}
