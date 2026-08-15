package io.infranexum.itam.asset;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.application.AssetCommandContext;
import io.infranexum.itam.asset.application.AssetPage;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.application.CreateAssetCommand;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import io.infranexum.itam.asset.domain.AssetCustodyEventType;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetNotFoundException;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.domain.AssetValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exhaustive invariants for PGM-07-E02 immutable ITAM asset value objects and lifecycle. */
final class AssetDomainTest {
    private static final DomainIdentifier ID = id(1);
    private static final DomainIdentifier RSOT = id(2);
    private static final DomainIdentifier ORG = id(3);
    private static final DomainIdentifier SUB = id(4);
    private static final DomainIdentifier ACTOR = id(5);
    private static final DomainIdentifier PARTNER = id(6);
    private static final DomainIdentifier CORR = id(7);
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    @Test
    void enumsExposeStableWireValuesAndRejectUnknownValues() {
        assertEquals(AssetType.HARDWARE, AssetType.parse(" HARDWARE "));
        assertEquals("software", AssetType.SOFTWARE.wireValue());
        assertThrows(NullPointerException.class, () -> AssetType.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AssetType.parse("service"));
        assertEquals(AssetCustodianKind.SUBDIVISION, AssetCustodianKind.parse("subdivision"));
        assertThrows(NullPointerException.class, () -> AssetCustodianKind.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AssetCustodianKind.parse("device"));
        assertTrue(AssetLifecycleStatus.IN_STOCK.operationalReadinessRequired());
        assertTrue(AssetLifecycleStatus.ASSIGNED.operationalReadinessRequired());
        assertTrue(AssetLifecycleStatus.DEPLOYED.operationalReadinessRequired());
        assertFalse(AssetLifecycleStatus.RECEIVED.operationalReadinessRequired());
        assertTrue(AssetLifecycleStatus.DISPOSED.terminal());
        assertFalse(AssetLifecycleStatus.RETIRED.terminal());
        assertEquals("MAINTENANCE_STARTED", AssetCustodyEventType.MAINTENANCE_STARTED.name());
    }

    @Test
    void assetValueNormalizesCurrencyAndRejectsInvalidMoney() {
        AssetValue value = new AssetValue(new BigDecimal("1250.2500"), " eur ");
        assertEquals(new BigDecimal("1250.2500"), value.amount());
        assertEquals("EUR", value.currencyCode());
        assertThrows(NullPointerException.class, () -> new AssetValue(null, "EUR"));
        assertThrows(NullPointerException.class, () -> new AssetValue(BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> new AssetValue(new BigDecimal("-0.01"), "EUR"));
        assertThrows(IllegalArgumentException.class, () -> new AssetValue(new BigDecimal("1.00001"), "EUR"));
        assertThrows(IllegalArgumentException.class, () -> new AssetValue(new BigDecimal("12345678901234567890"), "EUR"));
        for (String invalid : List.of("EU", "EURO", "E1R", "€€€")) {
            assertThrows(IllegalArgumentException.class, () -> new AssetValue(BigDecimal.ONE, invalid));
        }
    }

    @Test
    void custodianRequiresReferenceExceptForNone() {
        assertEquals(AssetCustodianKind.NONE, AssetCustodian.none().kind());
        assertNull(AssetCustodian.none().referenceId());
        assertEquals(ORG, AssetCustodian.organization(ORG).referenceId());
        assertThrows(NullPointerException.class, () -> new AssetCustodian(null, ORG));
        assertThrows(IllegalArgumentException.class, () -> new AssetCustodian(AssetCustodianKind.NONE, ORG));
        assertThrows(IllegalArgumentException.class, () -> new AssetCustodian(AssetCustodianKind.ACTOR, null));
        assertThrows(NullPointerException.class, () -> AssetCustodian.organization(null));
    }

    @Test
    void commandAndSearchContractsValidateBoundaries() {
        CreateAssetCommand command = new CreateAssetCommand(
                RSOT, "hardware", ORG, SUB, LocalDate.of(2026, 8, 1), BigDecimal.TEN, "EUR", PARTNER);
        assertEquals(RSOT, command.rsotObjectId());
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(null, "hardware", ORG, SUB, LocalDate.now(), BigDecimal.ONE, "EUR", null));
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(RSOT, null, ORG, SUB, LocalDate.now(), BigDecimal.ONE, "EUR", null));
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(RSOT, "hardware", null, SUB, LocalDate.now(), BigDecimal.ONE, "EUR", null));
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(RSOT, "hardware", ORG, SUB, null, BigDecimal.ONE, "EUR", null));
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(RSOT, "hardware", ORG, SUB, LocalDate.now(), null, "EUR", null));
        assertThrows(NullPointerException.class, () -> new CreateAssetCommand(RSOT, "hardware", ORG, SUB, LocalDate.now(), BigDecimal.ONE, null, null));

        AssetCommandContext context = new AssetCommandContext(ACTOR, CORR, " key-0001 ", " valid reason ", " evidence-1 ");
        assertEquals("key-0001", context.idempotencyKey());
        assertEquals("valid reason", context.reason());
        assertEquals("evidence-1", context.evidenceReference());
        assertNull(new AssetCommandContext(ACTOR, CORR, "key-0002", "valid reason", "  ").evidenceReference());
        assertThrows(IllegalArgumentException.class, () -> new AssetCommandContext(ACTOR, CORR, "short", "valid reason", null));
        assertThrows(IllegalArgumentException.class, () -> new AssetCommandContext(ACTOR, CORR, "key-0003", "x", null));
        assertThrows(IllegalArgumentException.class, () -> new AssetCommandContext(ACTOR, CORR, "key-0004", "valid reason", "x".repeat(241)));
        assertThrows(NullPointerException.class, () -> new AssetCommandContext(null, CORR, "key-0005", "valid reason", null));

        assertDoesNotThrow(() -> new AssetSearchCriteria(ORG, AssetType.HARDWARE, AssetLifecycleStatus.ACQUIRED, RSOT, null, 1));
        assertDoesNotThrow(() -> new AssetSearchCriteria(null, null, null, null, null, 200));
        assertThrows(IllegalArgumentException.class, () -> new AssetSearchCriteria(null, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssetSearchCriteria(null, null, null, null, null, 201));
        assertEquals(List.of(), new AssetPage(List.of(), null).items());
        assertThrows(NullPointerException.class, () -> new AssetPage(null, null));
    }

    @Test
    void custodyEventIsImmutableValidatedAndRequiresDispositionEvidence() {
        AssetCustodyEvent event = new AssetCustodyEvent(
                id(8), ID, 1, AssetCustodyEventType.ACQUIRED, null, AssetLifecycleStatus.ACQUIRED,
                AssetCustodian.organization(ORG), NOW, ACTOR, CORR, " Initial acquisition ", " receipt-1 ");
        assertEquals("Initial acquisition", event.reason());
        assertEquals("receipt-1", event.evidenceReference());
        assertThrows(IllegalArgumentException.class, () -> new AssetCustodyEvent(
                id(8), ID, 0, AssetCustodyEventType.ACQUIRED, null, AssetLifecycleStatus.ACQUIRED,
                AssetCustodian.organization(ORG), NOW, ACTOR, CORR, "valid reason", null));
        assertThrows(IllegalArgumentException.class, () -> new AssetCustodyEvent(
                id(8), ID, 2, AssetCustodyEventType.DISPOSED, AssetLifecycleStatus.RETIRED, AssetLifecycleStatus.DISPOSED,
                AssetCustodian.none(), NOW, ACTOR, CORR, "disposed", null));
        assertThrows(IllegalArgumentException.class, () -> new AssetCustodyEvent(
                id(8), ID, 2, AssetCustodyEventType.DISPOSED, AssetLifecycleStatus.RETIRED, AssetLifecycleStatus.DISPOSED,
                AssetCustodian.none(), NOW, ACTOR, CORR, "disposed", "x".repeat(241)));
        assertThrows(NullPointerException.class, () -> new AssetCustodyEvent(
                null, ID, 1, AssetCustodyEventType.ACQUIRED, null, AssetLifecycleStatus.ACQUIRED,
                AssetCustodian.organization(ORG), NOW, ACTOR, CORR, "valid reason", null));
    }

    @Test
    void lifecyclePreservesPatrimonialIdentityAndBuildsGovernedStateMachine() {
        Asset acquired = acquired();
        assertAll(
                () -> assertEquals(ID, acquired.id()),
                () -> assertEquals(RSOT, acquired.rsotObjectId()),
                () -> assertEquals(AssetType.HARDWARE, acquired.assetType()),
                () -> assertEquals(ORG, acquired.owningOrganizationId()),
                () -> assertEquals(SUB, acquired.owningSubdivisionId()),
                () -> assertEquals(PARTNER, acquired.acquiredFromPartnerId()),
                () -> assertEquals(AssetLifecycleStatus.ACQUIRED, acquired.lifecycleStatus()),
                () -> assertEquals(AssetCustodianKind.ORGANIZATION, acquired.custodian().kind()),
                () -> assertEquals(1, acquired.version()),
                () -> assertEquals(NOW, acquired.createdAt()),
                () -> assertEquals(ACTOR, acquired.createdBy()),
                () -> assertEquals("Initial acquisition", acquired.lastReason()));

        Asset received = acquired.receive(AssetCustodian.organization(ORG), ACTOR, "Received", NOW.plusSeconds(1));
        Asset stocked = received.stock(new AssetCustodian(AssetCustodianKind.SUBDIVISION, SUB), ACTOR, "Stocked", NOW.plusSeconds(2));
        Asset assigned = stocked.assign(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "Assigned", NOW.plusSeconds(3));
        Asset deployed = assigned.deploy(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "Deployed", NOW.plusSeconds(4));
        Asset transferred = deployed.transfer(new AssetCustodian(AssetCustodianKind.SUBDIVISION, SUB), ACTOR, "Transferred", NOW.plusSeconds(5));
        Asset maintenance = transferred.startMaintenance(new AssetCustodian(AssetCustodianKind.PARTNER, PARTNER), ACTOR, "Maintenance", NOW.plusSeconds(6));
        Asset returned = maintenance.returnFromMaintenance(AssetCustodian.organization(ORG), ACTOR, "Returned", NOW.plusSeconds(7));
        Asset retired = returned.retire(ACTOR, "Retired", NOW.plusSeconds(8));
        Asset disposed = retired.dispose(ACTOR, "Disposed", NOW.plusSeconds(9));
        assertEquals(10, disposed.version());
        assertEquals(AssetLifecycleStatus.DISPOSED, disposed.lifecycleStatus());
        assertEquals(AssetCustodianKind.NONE, disposed.custodian().kind());
        assertEquals(NOW, disposed.createdAt());
        assertEquals(RSOT, disposed.rsotObjectId());
        assertEquals("Disposed", disposed.lastReason());
    }

    @Test
    void lifecycleRejectsIllegalStatesCustodiansAndTemporalRegression() {
        Asset acquired = acquired();
        assertCode("ITAM_ASSET_STATE_CONFLICT", () -> acquired.deploy(AssetCustodian.organization(ORG), ACTOR, "invalid", NOW.plusSeconds(1)));
        Asset received = acquired.receive(AssetCustodian.organization(ORG), ACTOR, "received", NOW.plusSeconds(1));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> received.stock(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "bad stock", NOW.plusSeconds(2)));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> received.assign(new AssetCustodian(AssetCustodianKind.PARTNER, PARTNER), ACTOR, "bad assign", NOW.plusSeconds(2)));
        Asset assigned = received.assign(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "assigned", NOW.plusSeconds(2));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> assigned.deploy(new AssetCustodian(AssetCustodianKind.PARTNER, PARTNER), ACTOR, "bad deploy", NOW.plusSeconds(3)));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> received.transfer(AssetCustodian.none(), ACTOR, "bad transfer", NOW.plusSeconds(2)));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> received.startMaintenance(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "bad maintenance", NOW.plusSeconds(2)));
        Asset maintenance = received.startMaintenance(new AssetCustodian(AssetCustodianKind.PARTNER, PARTNER), ACTOR, "maintenance", NOW.plusSeconds(2));
        assertCode("ITAM_ASSET_CUSTODIAN_INVALID", () -> maintenance.returnFromMaintenance(new AssetCustodian(AssetCustodianKind.ACTOR, ACTOR), ACTOR, "bad return", NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> received.transfer(AssetCustodian.organization(ORG), ACTOR, "time regression", NOW));
        assertCode("ITAM_ASSET_STATE_CONFLICT", () -> acquired.retire(ACTOR, "invalid retire", NOW.plusSeconds(1)));
        assertCode("ITAM_ASSET_STATE_CONFLICT", () -> acquired.dispose(ACTOR, "invalid dispose", NOW.plusSeconds(1)));
    }

    @Test
    void restoreValidatesPersistedStateAndExceptionsExposeStableContracts() {
        Asset restored = Asset.restore(
                ID, RSOT, AssetType.SOFTWARE, ORG, null, LocalDate.of(2026, 1, 1), new AssetValue(BigDecimal.ONE, "USD"),
                null, AssetLifecycleStatus.RECEIVED, AssetCustodian.organization(ORG), 4,
                NOW, NOW.plusSeconds(1), ACTOR, ACTOR, "Restored from durable state");
        assertEquals(4, restored.version());
        assertEquals(AssetType.SOFTWARE, restored.assetType());
        assertThrows(IllegalArgumentException.class, () -> Asset.restore(
                ID, RSOT, AssetType.HARDWARE, ORG, null, LocalDate.now(), new AssetValue(BigDecimal.ONE, "EUR"), null,
                AssetLifecycleStatus.ACQUIRED, AssetCustodian.organization(ORG), 0, NOW, NOW, ACTOR, ACTOR, "valid reason"));
        assertThrows(IllegalArgumentException.class, () -> Asset.restore(
                ID, RSOT, AssetType.HARDWARE, ORG, null, LocalDate.now(), new AssetValue(BigDecimal.ONE, "EUR"), null,
                AssetLifecycleStatus.ACQUIRED, AssetCustodian.organization(ORG), 1, NOW, NOW.minusSeconds(1), ACTOR, ACTOR, "valid reason"));
        AssetConflictException conflict = new AssetConflictException("CODE", "message");
        assertEquals("CODE", conflict.code());
        assertEquals("message", conflict.getMessage());
        assertEquals("ITAM asset not found", new AssetNotFoundException().getMessage());
        assertEquals("itam.assets.max quota exceeded", new AssetQuotaException().getMessage());
    }

    private static Asset acquired() {
        return Asset.acquired(
                ID, RSOT, AssetType.HARDWARE, ORG, SUB, LocalDate.of(2026, 8, 1),
                new AssetValue(new BigDecimal("2500.00"), "EUR"), PARTNER, ACTOR, "Initial acquisition", NOW);
    }

    private static DomainIdentifier id(int suffix) {
        return DomainIdentifier.parse("01900000-0000-7000-8000-" + String.format("%012d", suffix));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        AssetConflictException failure = assertThrows(AssetConflictException.class, executable);
        assertEquals(code, failure.code());
    }
}
