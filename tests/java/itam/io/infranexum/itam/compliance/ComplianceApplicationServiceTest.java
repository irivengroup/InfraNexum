package io.infranexum.itam.compliance;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.InMemoryEventStore;
import io.infranexum.itam.asset.application.AssetPage;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodyEvent;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.domain.AssetValue;
import io.infranexum.itam.asset.ports.AssetRepository;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import io.infranexum.itam.compliance.application.ComplianceCommandContext;
import io.infranexum.itam.compliance.application.CreateLicenseCommand;
import io.infranexum.itam.compliance.application.CreateSupportAuthorizationCommand;
import io.infranexum.itam.compliance.application.CreateSupportCoverageCommand;
import io.infranexum.itam.compliance.application.CreateWarrantyCommand;
import io.infranexum.itam.compliance.domain.ComplianceAlert;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.ComplianceRevision;
import io.infranexum.itam.compliance.domain.ComplianceStatus;
import io.infranexum.itam.compliance.domain.SoftwareLicenseContract;
import io.infranexum.itam.compliance.domain.SupportCoverage;
import io.infranexum.itam.compliance.domain.SupportProviderAuthorization;
import io.infranexum.itam.compliance.domain.Warranty;
import io.infranexum.itam.compliance.domain.WarrantyType;
import io.infranexum.itam.compliance.ports.ComplianceFeaturePolicy;
import io.infranexum.itam.compliance.ports.ComplianceIdempotencyRepository;
import io.infranexum.itam.compliance.ports.ComplianceReferencePolicy;
import io.infranexum.itam.compliance.ports.ComplianceRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Dependency-free PGM-07-E03 smoke for warranty, support and software-license governance. */
final class ComplianceApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Test
    void governedComplianceLifecycleIsTransactionalIdempotentAndFailClosed() {
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {7, 0, 3}));
        DomainIdentifier organization = ids.next();
        DomainIdentifier subdivision = ids.next();
        DomainIdentifier actor = ids.next();
        DomainIdentifier correlation = ids.next();
        DomainIdentifier manufacturer = ids.next();
        DomainIdentifier publisher = ids.next();
        DomainIdentifier supportProvider = ids.next();
        DomainIdentifier hardwareId = ids.next();
        DomainIdentifier softwareId = ids.next();
        DomainIdentifier supplier = ids.next();

        Assets assets = new Assets();
        Asset hardware = asset(hardwareId, ids.next(), AssetType.HARDWARE, organization, subdivision, supplier, manufacturer, actor);
        Asset software = asset(softwareId, ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        assets.values.put(hardware.id(), hardware);
        assets.values.put(software.id(), software);

        Repository repository = new Repository();
        Idempotency idempotency = new Idempotency();
        InMemoryEventStore events = new InMemoryEventStore();
        References references = new References(manufacturer, publisher, supportProvider, organization, subdivision, repository);
        ComplianceApplicationService service = service(assets, repository, idempotency, references, events, ids, true, 4);

        WarrantyType type = service.createWarrantyType("manufacturer_standard", "Manufacturer standard warranty",
                context(actor, correlation, "warranty-type-0001", "Governed warranty catalogue initialization"));
        require(type.active(), "warranty type must be active");
        WarrantyType typeReplay = service.createWarrantyType("manufacturer_standard", "Manufacturer standard warranty",
                context(actor, correlation, "warranty-type-0001", "Governed warranty catalogue initialization"));
        require(typeReplay.id().equals(type.id()), "warranty-type idempotent replay failed");
        service.createWarrantyType("extended_support", "Extended support warranty",
                context(actor, correlation, "warranty-type-0002", "Second governed warranty type"));
        var warrantyTypesPage1 = service.warrantyTypePage(0, 1);
        require(warrantyTypesPage1.items().size() == 1 && Integer.valueOf(1).equals(warrantyTypesPage1.nextOffset()), "warranty type first page");
        var warrantyTypesPage2 = service.warrantyTypePage(1, 1);
        require(warrantyTypesPage2.items().size() == 1 && warrantyTypesPage2.nextOffset() == null, "warranty type second page");
        expectIllegal(() -> service.warrantyTypePage(PaginationConstraints.MAX_OFFSET + 1, 1));

        CreateWarrantyCommand warrantyCommand = new CreateWarrantyCommand(
                hardware.id(), manufacturer, type.id(), "parts_labour_onsite",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 15),
                "W-2026-0001", "evidence:warranty:0001", "manual");
        ComplianceCommandContext warrantyCreate = context(actor, correlation, "warranty-create-0001", "Manufacturer evidence verified for import");
        Warranty warranty = service.createWarranty(warrantyCommand, warrantyCreate);
        require(warranty.status() == ComplianceStatus.DRAFT, "warranty must start draft");
        require(!service.hardwareReady(hardware, TODAY), "draft warranty must not unlock operational state");
        Warranty warrantyReplay = service.createWarranty(warrantyCommand, warrantyCreate);
        require(warrantyReplay.id().equals(warranty.id()), "warranty idempotent replay failed");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.createWarranty(
                new CreateWarrantyCommand(hardware.id(), manufacturer, type.id(), "different_coverage",
                        warrantyCommand.warrantyStartDate(), warrantyCommand.warrantyEndDate(), warrantyCommand.manufacturerSupportEndDate(),
                        warrantyCommand.contractOrCertificateNumber(), warrantyCommand.proofReference(), warrantyCommand.source()), warrantyCreate));
        expectCode("ITAM_COMPLIANCE_PRODUCER_MISMATCH", () -> service.createWarranty(
                new CreateWarrantyCommand(hardware.id(), publisher, type.id(), "invalid",
                        warrantyCommand.warrantyStartDate(), warrantyCommand.warrantyEndDate(), warrantyCommand.manufacturerSupportEndDate(),
                        "BAD", "evidence:bad", "manual"),
                context(actor, correlation, "warranty-create-0002", "Reject mismatched manufacturer")));

        Warranty activeWarranty = service.activateWarranty(warranty.id(), 1,
                context(actor, correlation, "warranty-activate-0001", "Warranty proof accepted by verifier"));
        require(activeWarranty.status() == ComplianceStatus.ACTIVE && activeWarranty.version() == 2,
                "warranty activation failed");
        require(service.hardwareReady(hardware, TODAY), "active verified warranty must unlock hardware readiness");
        expectCode("VERSION_CONFLICT", () -> service.activateWarranty(warranty.id(), 1,
                context(actor, correlation, "warranty-stale-0001", "Stale warranty version must fail")));

        List<ComplianceAlert> alerts = service.upcomingAlerts(hardware.id(), TODAY, 180);
        require(alerts.stream().anyMatch(a -> a.thresholdDays() == 15 && a.dueDate().equals(LocalDate.of(2026, 8, 30))),
                "J-15 warranty alert missing");
        int firstPublished = service.publishDueAlerts(TODAY, correlation);
        int secondPublished = service.publishDueAlerts(TODAY, correlation);
        require(firstPublished >= 1 && secondPublished == 0, "deadline alert deduplication failed");

        CreateSupportAuthorizationCommand authCommand = new CreateSupportAuthorizationCommand(
                supportProvider, organization, Set.of(manufacturer), Set.of("server"), Set.of(subdivision),
                "24x7", "Europe/Paris", Set.of("gold"), Set.of("support"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));
        SupportProviderAuthorization authorization = service.createSupportAuthorization(authCommand,
                context(actor, correlation, "support-auth-create-0001", "Provider authorization evidence accepted"));
        authorization = service.activateSupportAuthorization(authorization.id(), 1,
                context(actor, correlation, "support-auth-activate-0001", "Provider scope approved"));
        require(authorization.status() == ComplianceStatus.ACTIVE, "support authorization activation failed");

        CreateSupportCoverageCommand coverageCommand = new CreateSupportCoverageCommand(
                hardware.id(), supportProvider, authorization.id(), "SUP-2026-0001", "hardware_maintenance", "gold",
                LocalDate.of(2026, 8, 31), LocalDate.of(2027, 8, 31), "evidence:support:0001");
        SupportCoverage coverage = service.createSupportCoverage(coverageCommand,
                context(actor, correlation, "support-coverage-create-0001", "Third-party contract scope accepted"));
        coverage = service.activateSupportCoverage(coverage.id(), 1,
                context(actor, correlation, "support-coverage-activate-0001", "Support coverage approved"));
        require(coverage.status() == ComplianceStatus.ACTIVE, "support coverage activation failed");
        require(service.hardwareReady(hardware, LocalDate.of(2026, 10, 1)),
                "authorized third-party support must extend hardware supportability after warranty end");

        SupportProviderAuthorization suspended = service.suspendSupportAuthorization(authorization.id(), 2,
                context(actor, correlation, "support-auth-suspend-0001", "Provider authorization suspended after governance review"));
        require(suspended.status() == ComplianceStatus.REVIEW_REQUIRED, "support authorization suspension failed");
        SupportCoverage review = service.getSupportCoverage(coverage.id());
        require(review.status() == ComplianceStatus.REVIEW_REQUIRED,
                "active coverage must become review-required when provider authorization is suspended");
        require(!service.hardwareReady(hardware, LocalDate.of(2026, 10, 1)),
                "review-required coverage must not extend hardware readiness");

        CreateLicenseCommand licenseCommand = new CreateLicenseCommand(
                software.id(), publisher, "LIC-2026-0001", "subscription", "production use; one managed instance",
                1, LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31), LocalDate.of(2027, 7, 31),
                "evidence:license:0001", "manual");
        SoftwareLicenseContract license = service.createLicense(licenseCommand,
                context(actor, correlation, "license-create-0001", "Software entitlement contract imported"));
        require(!service.softwareReady(software, TODAY), "draft license must not unlock software readiness");
        license = service.activateLicense(license.id(), 1,
                context(actor, correlation, "license-activate-0001", "Software entitlement proof verified"));
        require(license.status() == ComplianceStatus.ACTIVE && service.softwareReady(software, TODAY),
                "active verified license must unlock software readiness");

        require(service.warrantyPage(hardware.id(), null, 1).items().size() == 1, "warranty pagination failed");
        require(service.licensePage(software.id(), null, 1).items().size() == 1, "license pagination failed");
        require(service.supportCoveragePage(hardware.id(), null, 1).items().size() == 1, "support pagination failed");
        require(!service.history("warranty", warranty.id(), 0, 20).isEmpty(), "warranty version history missing");
        require(events.outboxSnapshot().stream().anyMatch(record ->
                        "itam.support_coverage.review_required.v1".equals(record.event().eventType().value())),
                "support review-required event missing");

        // Contract quota counts software licenses and support coverages, not mandatory manufacturer warranties or provider authorizations.
        Asset software2 = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        Asset software3 = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        assets.values.put(software2.id(), software2);
        assets.values.put(software3.id(), software3);
        service.createLicense(new CreateLicenseCommand(software2.id(), publisher, "LIC-2026-0002", "subscription", "production use",
                        1, TODAY, LocalDate.of(2027, 8, 14), LocalDate.of(2027, 8, 14), "evidence:license:0002", "manual"),
                context(actor, correlation, "license-create-0002", "Second governed license"));
        service.createLicense(new CreateLicenseCommand(software3.id(), publisher, "LIC-2026-0003", "subscription", "production use",
                        1, TODAY, LocalDate.of(2027, 8, 14), LocalDate.of(2027, 8, 14), "evidence:license:0003", "manual"),
                context(actor, correlation, "license-create-0003", "Third governed license"));
        Asset software4 = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        assets.values.put(software4.id(), software4);
        expectCode("ITAM_CONTRACT_QUOTA_EXCEEDED", () -> service.createLicense(
                new CreateLicenseCommand(software4.id(), publisher, "LIC-2026-0004", "subscription", "production use",
                        1, TODAY, LocalDate.of(2027, 8, 14), LocalDate.of(2027, 8, 14), "evidence:license:0004", "manual"),
                context(actor, correlation, "license-create-0004", "Quota boundary verification")));

        ComplianceApplicationService disabled = service(assets, repository, idempotency, references, events, ids, false, 4);
        expectCode("ITAM_COMPLIANCE_CAPABILITY_UNAVAILABLE", () -> disabled.getWarranty(warranty.id()));
        require(!disabled.hardwareReady(hardware, TODAY), "disabled compliance capability must fail readiness closed");

    }


    @Test
    void coverageRegressionExercisesMutationReplayPagingReadinessAndAlertBoundaries() {
        UuidV7Generator ids = new UuidV7Generator(CLOCK, new SecureRandom(new byte[] {1, 2, 3, 4}));
        DomainIdentifier organization = ids.next();
        DomainIdentifier subdivision = ids.next();
        DomainIdentifier actor = ids.next();
        DomainIdentifier correlation = ids.next();
        DomainIdentifier manufacturer = ids.next();
        DomainIdentifier publisher = ids.next();
        DomainIdentifier supportProvider = ids.next();
        DomainIdentifier supplier = ids.next();

        Assets assets = new Assets();
        Asset hardware = asset(ids.next(), ids.next(), AssetType.HARDWARE, organization, subdivision, supplier, manufacturer, actor);
        Asset hardwareWithoutProducer = asset(ids.next(), ids.next(), AssetType.HARDWARE, organization, subdivision, supplier, null, actor);
        Asset otherHardware = asset(ids.next(), ids.next(), AssetType.HARDWARE, organization, subdivision, supplier, manufacturer, actor);
        Asset software = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        Asset otherSoftware = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, publisher, actor);
        assets.values.put(hardware.id(), hardware);
        assets.values.put(hardwareWithoutProducer.id(), hardwareWithoutProducer);
        assets.values.put(otherHardware.id(), otherHardware);
        assets.values.put(software.id(), software);
        assets.values.put(otherSoftware.id(), otherSoftware);

        Repository repository = new Repository();
        Idempotency idempotency = new Idempotency();
        InMemoryEventStore events = new InMemoryEventStore();
        References references = new References(manufacturer, publisher, supportProvider, organization, subdivision, repository);
        ComplianceApplicationService service = service(assets, repository, idempotency, references, events, ids, true, 20);

        WarrantyType type = service.createWarrantyType("manufacturer_standard", "Manufacturer standard warranty",
                context(actor, correlation, "edge-type-0001", "Create warranty type"));
        CreateWarrantyCommand warrantyCommand = new CreateWarrantyCommand(
                hardware.id(), manufacturer, type.id(), "standard", TODAY.minusMonths(1), TODAY.plusDays(15),
                TODAY.plusDays(30), "W-EDGE-1", "evidence:warranty:edge", "manual");
        Warranty warranty = service.createWarranty(warrantyCommand,
                context(actor, correlation, "edge-warranty-0001", "Create warranty"));
        Warranty replayWarranty = service.createWarranty(warrantyCommand,
                context(actor, correlation, "edge-warranty-0001", "Create warranty"));
        require(replayWarranty.id().equals(warranty.id()), "warranty replay must return original record");
        CreateWarrantyCommand otherWarrantyAsset = new CreateWarrantyCommand(
                otherHardware.id(), manufacturer, type.id(), "standard", TODAY.minusMonths(1), TODAY.plusDays(15),
                TODAY.plusDays(30), "W-EDGE-2", "evidence:warranty:edge2", "manual");
        expectCode("ITAM_WARRANTY_ASSET_IMMUTABLE", () -> service.reviseWarranty(
                warranty.id(), warranty.version(), otherWarrantyAsset,
                context(actor, correlation, "edge-warranty-revise-immutable", "Reject immutable asset")));
        Warranty revisedWarranty = service.reviseWarranty(warranty.id(), warranty.version(), warrantyCommand,
                context(actor, correlation, "edge-warranty-revise", "Revise warranty"));
        ComplianceCommandContext warrantyActivateContext = context(actor, correlation, "edge-warranty-activate", "Activate warranty");
        long warrantyVersionBeforeActivation = revisedWarranty.version();
        Warranty activeWarranty = service.activateWarranty(revisedWarranty.id(), warrantyVersionBeforeActivation, warrantyActivateContext);
        require(service.activateWarranty(revisedWarranty.id(), warrantyVersionBeforeActivation, warrantyActivateContext).id().equals(activeWarranty.id()),
                "warranty activation replay must return original record");
        expectIllegal(() -> service.expireWarranty(activeWarranty.id(), 0,
                context(actor, correlation, "edge-warranty-expire-invalid-version", "Invalid version")));

        CreateLicenseCommand licenseCommand = new CreateLicenseCommand(
                software.id(), publisher, "LIC-EDGE-1", "subscription", "production", 1,
                TODAY.minusDays(1), TODAY.plusDays(30), TODAY.plusDays(60), "evidence:license:edge", "manual");
        ComplianceCommandContext licenseContext = context(actor, correlation, "edge-license-0001", "Create license");
        SoftwareLicenseContract license = service.createLicense(licenseCommand, licenseContext);
        require(service.createLicense(licenseCommand, licenseContext).id().equals(license.id()), "license replay must return original record");
        CreateLicenseCommand otherLicenseAsset = new CreateLicenseCommand(
                otherSoftware.id(), publisher, "LIC-EDGE-2", "subscription", "production", 1,
                TODAY.minusDays(1), TODAY.plusDays(30), TODAY.plusDays(60), "evidence:license:edge2", "manual");
        expectCode("ITAM_LICENSE_ASSET_IMMUTABLE", () -> service.reviseLicense(license.id(), license.version(), otherLicenseAsset,
                context(actor, correlation, "edge-license-revise-immutable", "Reject immutable asset")));
        SoftwareLicenseContract revisedLicense = service.reviseLicense(license.id(), license.version(), licenseCommand,
                context(actor, correlation, "edge-license-revise", "Revise license"));
        ComplianceCommandContext licenseActivateContext = context(actor, correlation, "edge-license-activate", "Activate license");
        long licenseVersionBeforeActivation = revisedLicense.version();
        SoftwareLicenseContract activeLicense = service.activateLicense(revisedLicense.id(), licenseVersionBeforeActivation, licenseActivateContext);
        require(service.activateLicense(revisedLicense.id(), licenseVersionBeforeActivation, licenseActivateContext).id().equals(activeLicense.id()),
                "license activation replay must return original record");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.activateLicense(revisedLicense.id(), licenseVersionBeforeActivation, licenseContext));
        CreateLicenseCommand changedLicensePayload = new CreateLicenseCommand(
                software.id(), publisher, "LIC-EDGE-CHANGED", "subscription", "production", 1,
                TODAY.minusDays(1), TODAY.plusDays(30), TODAY.plusDays(60), "evidence:license:edge", "manual");
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.createLicense(changedLicensePayload, licenseContext));
        ComplianceIdempotencyRepository.Record originalLicenseRecord = idempotency.values.get(licenseContext.idempotencyKey());
        idempotency.values.put("edge-license-wrong-type", new ComplianceIdempotencyRepository.Record(
                "edge-license-wrong-type", originalLicenseRecord.payloadSha256(), originalLicenseRecord.operation(),
                "warranty", originalLicenseRecord.recordId(), originalLicenseRecord.createdAt()));
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.createLicense(licenseCommand,
                context(actor, correlation, "edge-license-wrong-type", "Create license")));

        CreateSupportAuthorizationCommand authCommand = new CreateSupportAuthorizationCommand(
                supportProvider, organization, Set.of(manufacturer), Set.of("server"), Set.of(subdivision),
                "24x7", "UTC", Set.of("gold"), Set.of("support"), TODAY.minusMonths(1), TODAY.plusYears(1));
        ComplianceCommandContext authContext = context(actor, correlation, "edge-auth-0001", "Create authorization");
        SupportProviderAuthorization authorization = service.createSupportAuthorization(authCommand, authContext);
        require(service.createSupportAuthorization(authCommand, authContext).id().equals(authorization.id()),
                "authorization replay must return original record");
        ComplianceCommandContext authActivateContext = context(actor, correlation, "edge-auth-activate", "Activate authorization");
        long authVersionBeforeActivation = authorization.version();
        authorization = service.activateSupportAuthorization(authorization.id(), authVersionBeforeActivation, authActivateContext);
        require(service.activateSupportAuthorization(authorization.id(), authVersionBeforeActivation, authActivateContext).id().equals(authorization.id()),
                "authorization activation replay must return original record");

        CreateSupportCoverageCommand coverageCommand = new CreateSupportCoverageCommand(
                hardware.id(), supportProvider, authorization.id(), "SUP-EDGE-1", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:edge");
        ComplianceCommandContext coverageContext = context(actor, correlation, "edge-coverage-0001", "Create coverage");
        SupportCoverage coverage = service.createSupportCoverage(coverageCommand, coverageContext);
        require(service.createSupportCoverage(coverageCommand, coverageContext).id().equals(coverage.id()),
                "coverage replay must return original record");
        CreateSupportCoverageCommand providerMismatch = new CreateSupportCoverageCommand(
                hardware.id(), publisher, authorization.id(), "SUP-EDGE-X", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:mismatch");
        expectCode("ITAM_SUPPORT_AUTH_PROVIDER_MISMATCH", () -> service.createSupportCoverage(providerMismatch,
                context(actor, correlation, "edge-coverage-provider-mismatch", "Reject provider mismatch")));
        CreateSupportCoverageCommand missingProducer = new CreateSupportCoverageCommand(
                hardwareWithoutProducer.id(), supportProvider, authorization.id(), "SUP-EDGE-NP", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:no-producer");
        expectCode("ITAM_ASSET_PRODUCER_REQUIRED", () -> service.createSupportCoverage(missingProducer,
                context(actor, correlation, "edge-coverage-no-producer", "Reject missing producer")));

        CreateSupportCoverageCommand changedAsset = new CreateSupportCoverageCommand(
                otherHardware.id(), supportProvider, authorization.id(), "SUP-EDGE-1", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:edge");
        expectCode("ITAM_SUPPORT_COVERAGE_IDENTITY_IMMUTABLE", () -> service.reviseSupportCoverage(
                coverage.id(), coverage.version(), changedAsset,
                context(actor, correlation, "edge-coverage-change-asset", "Reject changed asset")));
        CreateSupportCoverageCommand changedProvider = new CreateSupportCoverageCommand(
                hardware.id(), publisher, authorization.id(), "SUP-EDGE-1", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:edge");
        expectCode("ITAM_SUPPORT_COVERAGE_IDENTITY_IMMUTABLE", () -> service.reviseSupportCoverage(
                coverage.id(), coverage.version(), changedProvider,
                context(actor, correlation, "edge-coverage-change-provider", "Reject changed provider")));
        DomainIdentifier otherAuthorizationId = ids.next();
        CreateSupportCoverageCommand changedAuthorization = new CreateSupportCoverageCommand(
                hardware.id(), supportProvider, otherAuthorizationId, "SUP-EDGE-1", "hardware_maintenance", "gold",
                TODAY, TODAY.plusYears(1), "evidence:support:edge");
        expectCode("ITAM_SUPPORT_COVERAGE_IDENTITY_IMMUTABLE", () -> service.reviseSupportCoverage(
                coverage.id(), coverage.version(), changedAuthorization,
                context(actor, correlation, "edge-coverage-change-auth", "Reject changed authorization")));
        SupportCoverage revisedCoverage = service.reviseSupportCoverage(coverage.id(), coverage.version(), coverageCommand,
                context(actor, correlation, "edge-coverage-revise", "Revise coverage"));
        ComplianceCommandContext coverageActivateContext = context(actor, correlation, "edge-coverage-activate", "Activate coverage");
        long coverageVersionBeforeActivation = revisedCoverage.version();
        SupportCoverage activeCoverage = service.activateSupportCoverage(revisedCoverage.id(), coverageVersionBeforeActivation, coverageActivateContext);
        require(service.activateSupportCoverage(revisedCoverage.id(), coverageVersionBeforeActivation, coverageActivateContext).id().equals(activeCoverage.id()),
                "coverage activation replay must return original record");
        service.suspendSupportAuthorization(authorization.id(), authorization.version(),
                context(actor, correlation, "edge-auth-suspend", "Suspend authorization"));
        require(service.getSupportCoverage(activeCoverage.id()).status() == ComplianceStatus.REVIEW_REQUIRED,
                "active coverage must become review-required after authorization suspension");

        // A linked DRAFT coverage exercises the non-ACTIVE propagation branch on authorization suspension.
        SupportProviderAuthorization unreferenced = service.createSupportAuthorization(authCommand,
                context(actor, correlation, "edge-auth-unreferenced", "Create unreferenced authorization"));
        unreferenced = service.activateSupportAuthorization(unreferenced.id(), unreferenced.version(),
                context(actor, correlation, "edge-auth-unreferenced-activate", "Activate unreferenced authorization"));
        SupportCoverage draftCoverage = service.createSupportCoverage(new CreateSupportCoverageCommand(
                        hardware.id(), supportProvider, unreferenced.id(), "SUP-DRAFT", "hardware_maintenance", "gold",
                        TODAY, TODAY.plusYears(1), "evidence:support:draft"),
                context(actor, correlation, "edge-coverage-draft", "Create draft coverage"));
        require(draftCoverage.status() == ComplianceStatus.DRAFT, "draft coverage expected");
        ComplianceCommandContext suspendContext = context(actor, correlation, "edge-auth-unreferenced-suspend", "Suspend with draft coverage");
        long suspendVersion = unreferenced.version();
        SupportProviderAuthorization suspendedUnreferenced = service.suspendSupportAuthorization(unreferenced.id(), suspendVersion, suspendContext);
        require(service.suspendSupportAuthorization(unreferenced.id(), suspendVersion, suspendContext).id().equals(suspendedUnreferenced.id()),
                "authorization suspension replay must return original record");

        CreateWarrantyCommand softwareWarranty = new CreateWarrantyCommand(
                software.id(), manufacturer, type.id(), "standard", TODAY, TODAY.plusDays(30), TODAY.plusDays(60),
                "W-SOFTWARE", "evidence:warranty:software", "manual");
        expectCode("ITAM_COMPLIANCE_ASSET_TYPE_INVALID", () -> service.createWarranty(softwareWarranty,
                context(actor, correlation, "edge-warranty-software", "Reject warranty on software")));
        CreateWarrantyCommand producerlessWarranty = new CreateWarrantyCommand(
                hardwareWithoutProducer.id(), manufacturer, type.id(), "standard", TODAY, TODAY.plusDays(30), TODAY.plusDays(60),
                "W-NOPROD", "evidence:warranty:no-producer", "manual");
        expectCode("ITAM_ASSET_PRODUCER_REQUIRED", () -> service.createWarranty(producerlessWarranty,
                context(actor, correlation, "edge-warranty-no-producer", "Reject producerless warranty")));

        Warranty pastWarranty = Warranty.draft(ids.next(), hardware.id(), manufacturer, type.id(), "standard",
                TODAY.minusYears(1), TODAY.minusDays(1), TODAY.plusDays(4000), "W-PAST", "evidence:past",
                io.infranexum.itam.compliance.domain.EvidenceSource.MANUAL, actor, "Past warranty", NOW);
        repository.insertWarranty(pastWarranty);
        SoftwareLicenseContract alertLicense = SoftwareLicenseContract.draft(ids.next(), software.id(), publisher, "LIC-ALERT",
                "subscription", "production", 1, TODAY.minusDays(1), TODAY.plusDays(30), TODAY.plusDays(60),
                "evidence:license:alert", io.infranexum.itam.compliance.domain.EvidenceSource.MANUAL, actor, "Alert license", NOW);
        repository.insertLicense(alertLicense);
        SoftwareLicenseContract openEndedAlertLicense = SoftwareLicenseContract.draft(ids.next(), software.id(), publisher, "LIC-OPEN",
                "perpetual", "production", 1, TODAY.minusDays(1), null, TODAY.plusDays(30),
                "evidence:license:open", io.infranexum.itam.compliance.domain.EvidenceSource.MANUAL, actor, "Open license", NOW);
        repository.insertLicense(openEndedAlertLicense);
        require(!service.upcomingAlerts(software.id(), TODAY, 180).isEmpty(), "software alert path must be exercised");
        service.publishDueAlerts(TODAY, correlation);

        require(service.supportAuthorizationPage(organization, 0, 1).items().size() == 1, "support authorization page size");
        require(service.supportAuthorizationPage(organization, 0, 1).nextOffset() != null, "support authorization page next offset");
        expectIllegal(() -> service.supportAuthorizationPage(organization, 0, 0));
        expectIllegal(() -> service.supportAuthorizationPage(organization, 0, 201));
        expectIllegal(() -> service.history("warranty", warranty.id(), -1, 20));
        expectIllegal(() -> service.history("warranty", warranty.id(), 0, 0));
        expectIllegal(() -> service.history("warranty", warranty.id(), 0, 201));
        expectIllegal(() -> service.upcomingAlerts(hardware.id(), TODAY, 0));
        expectIllegal(() -> service.upcomingAlerts(hardware.id(), TODAY, 3651));
        require(service.upcomingAlertPage(hardware.id(), TODAY, 3650, 0, 20) != null, "upcoming alert page");
        require(!service.hardwareReady(software, TODAY), "software asset cannot be hardware-ready");
        require(!service.hardwareReady(hardwareWithoutProducer, TODAY), "producerless hardware cannot be ready");
        require(!service.softwareReady(hardware, TODAY), "hardware asset cannot be software-ready");
        require(!service.softwareReady(hardwareWithoutProducer, TODAY), "producerless hardware cannot be software-ready");
        Asset producerlessSoftware = asset(ids.next(), ids.next(), AssetType.SOFTWARE, organization, subdivision, supplier, null, actor);
        assets.values.put(producerlessSoftware.id(), producerlessSoftware);
        require(!service.softwareReady(producerlessSoftware, TODAY), "producerless software cannot be ready");
        ComplianceApplicationService disabled = service(assets, repository, idempotency, references, events, ids, false, 20);
        require(!disabled.softwareReady(software, TODAY), "disabled compliance must fail software readiness closed");
        expectIllegal(() -> service.warrantyPage(hardware.id(), null, 0));
        expectIllegal(() -> service.warrantyPage(hardware.id(), null, 201));

        // Warranty-type replay conflict and missing replay record remain fail-closed.
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.createWarrantyType("manufacturer_standard", "Different display",
                context(actor, correlation, "edge-type-0001", "Create warranty type")));
        expectCode("IDEMPOTENCY_CONFLICT", () -> service.createWarrantyType("CROSS_OPERATION", "Cross operation", licenseContext));

        expectIllegal(() -> new ComplianceApplicationService(
                assets, repository, idempotency, references, new Features(true, 20), events, ids, CLOCK, new int[] {}));
        expectIllegal(() -> new ComplianceApplicationService(
                assets, repository, idempotency, references, new Features(true, 20), events, ids, CLOCK, new int[] {30, 30}));
        expectIllegal(() -> new ComplianceApplicationService(
                assets, repository, idempotency, references, new Features(true, 20), events, ids, CLOCK, new int[] {0}));
        expectIllegal(() -> new ComplianceApplicationService(
                assets, repository, idempotency, references, new Features(true, 20), events, ids, CLOCK, new int[] {3651}));
        expectIllegal(() -> new ComplianceApplicationService(
                assets, repository, idempotency, references, new Features(true, 20), events, ids, CLOCK, new int[33]));
    }

    private static ComplianceApplicationService service(
            AssetRepository assets, ComplianceRepository repository, ComplianceIdempotencyRepository idempotency,
            ComplianceReferencePolicy references, InMemoryEventStore events, UuidV7Generator ids, boolean enabled, long limit) {
        return new ComplianceApplicationService(assets, repository, idempotency, references,
                new Features(enabled, limit), events, ids, CLOCK);
    }

    private static Asset asset(
            DomainIdentifier id, DomainIdentifier rsot, AssetType type, DomainIdentifier organization,
            DomainIdentifier subdivision, DomainIdentifier supplier, DomainIdentifier producer, DomainIdentifier actor) {
        return Asset.acquired(id, rsot, type, organization, subdivision, LocalDate.of(2026, 8, 1),
                new AssetValue(new BigDecimal("2500.0000"), "EUR"), supplier, producer,
                actor, "Governed acquisition", NOW);
    }

    private static ComplianceCommandContext context(
            DomainIdentifier actor, DomainIdentifier correlation, String key, String reason) {
        return new ComplianceCommandContext(actor, correlation, key, reason);
    }

    private record Features(boolean complianceEnabled, long contractLimit) implements ComplianceFeaturePolicy {}

    private record References(
            DomainIdentifier manufacturer, DomainIdentifier publisher, DomainIdentifier provider,
            DomainIdentifier organization, DomainIdentifier subdivision, Repository repository) implements ComplianceReferencePolicy {
        @Override public void validateManufacturer(Asset asset, DomainIdentifier id, LocalDate effectiveOn) {
            require(manufacturer.equals(id) && organization.equals(asset.owningOrganizationId()), "invalid manufacturer");
        }
        @Override public void validatePublisher(Asset asset, DomainIdentifier id, LocalDate effectiveOn) {
            require(publisher.equals(id) && organization.equals(asset.owningOrganizationId()), "invalid publisher");
        }
        @Override public void validateSupportProvider(
                Asset asset, DomainIdentifier id, LocalDate effectiveOn, Set<String> escalationContactTypes) {
            require(provider.equals(id) && escalationContactTypes.contains("support"), "invalid support provider");
        }
        @Override public void validateSupportAuthorizationDefinition(
                DomainIdentifier providerId, DomainIdentifier organizationId, Set<DomainIdentifier> manufacturers,
                Set<String> objectTypes, Set<DomainIdentifier> subdivisions, Set<String> escalationContactTypes,
                LocalDate effectiveOn) {
            require(provider.equals(providerId) && organization.equals(organizationId), "invalid authorization owner");
            require(manufacturers.equals(Set.of(manufacturer)), "invalid authorization manufacturer scope");
            require(objectTypes.equals(Set.of("server")), "invalid authorization product scope");
            require(subdivisions.equals(Set.of(subdivision)), "invalid authorization subdivision scope");
            require(escalationContactTypes.contains("support"), "missing escalation contact");
        }
        @Override public String canonicalObjectType(Asset asset) { return "server"; }
        @Override public void validateWarrantyType(DomainIdentifier id) {
            require(repository.findWarrantyType(id).filter(WarrantyType::active).isPresent(), "invalid warranty type");
        }
        @Override public void validateSupportAuthorization(
                Asset asset, SupportProviderAuthorization authorization, String serviceLevel, LocalDate effectiveOn) {
            require(authorization.providerPartnerId().equals(provider), "authorization provider mismatch");
            require(authorization.covers(asset.producerPartnerId(), "server", asset.owningSubdivisionId(), serviceLevel, effectiveOn),
                    "authorization does not cover asset scope");
        }
    }

    private static final class Idempotency implements ComplianceIdempotencyRepository {
        private final Map<String, Record> values = new LinkedHashMap<>();
        @Override public Optional<Record> find(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void insert(Record record) {
            if (values.putIfAbsent(record.key(), record) != null) throw new IllegalStateException("duplicate idempotency key");
        }
    }

    private static final class Assets implements AssetRepository {
        private final Map<DomainIdentifier, Asset> values = new LinkedHashMap<>();
        @Override public long count() { return values.size(); }
        @Override public boolean existsByRsotObjectId(DomainIdentifier rsotObjectId) {
            return values.values().stream().anyMatch(a -> a.rsotObjectId().equals(rsotObjectId));
        }
        @Override public Optional<Asset> findById(DomainIdentifier id) { return Optional.ofNullable(values.get(id)); }
        @Override public void insert(Asset asset, AssetCustodyEvent acquisitionEvent) { values.put(asset.id(), asset); }
        @Override public void update(Asset asset, long expectedVersion, AssetCustodyEvent custodyEvent) { values.put(asset.id(), asset); }
        @Override public void updateMetadata(Asset asset, long expectedVersion) { values.put(asset.id(), asset); }
        @Override public AssetPage search(AssetSearchCriteria criteria) { return new AssetPage(List.of(), null); }
        @Override public List<AssetCustodyEvent> custodyHistory(DomainIdentifier assetId, long afterSequence, int limit) { return List.of(); }
    }

    private static final class Repository implements ComplianceRepository {
        private final Map<DomainIdentifier, Warranty> warranties = new LinkedHashMap<>();
        private final Map<DomainIdentifier, SoftwareLicenseContract> licenses = new LinkedHashMap<>();
        private final Map<DomainIdentifier, SupportProviderAuthorization> authorizations = new LinkedHashMap<>();
        private final Map<DomainIdentifier, SupportCoverage> coverages = new LinkedHashMap<>();
        private final Map<DomainIdentifier, WarrantyType> warrantyTypes = new LinkedHashMap<>();
        private final Map<String, List<ComplianceRevision>> revisions = new LinkedHashMap<>();
        private final Set<String> alertKeys = new HashSet<>();

        @Override public long contractRecordCount() { return licenses.size() + coverages.size(); }
        @Override public Optional<Warranty> findWarranty(DomainIdentifier id) { return Optional.ofNullable(warranties.get(id)); }
        @Override public List<Warranty> warrantiesForAsset(DomainIdentifier assetId) { return byAsset(warranties.values(), assetId, Warranty::assetId); }
        @Override public List<Warranty> warrantyPage(DomainIdentifier assetId, DomainIdentifier afterId, int limit) { return page(warrantiesForAsset(assetId), afterId, limit, Warranty::id); }
        @Override public void insertWarranty(Warranty value) { warranties.put(value.id(), value); revision("warranty", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }
        @Override public void updateWarranty(Warranty value, long expectedVersion) { version(warranties.get(value.id()), expectedVersion, Warranty::version); warranties.put(value.id(), value); revision("warranty", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }

        @Override public Optional<SoftwareLicenseContract> findLicense(DomainIdentifier id) { return Optional.ofNullable(licenses.get(id)); }
        @Override public List<SoftwareLicenseContract> licensesForAsset(DomainIdentifier assetId) { return byAsset(licenses.values(), assetId, SoftwareLicenseContract::assetId); }
        @Override public List<SoftwareLicenseContract> licensePage(DomainIdentifier assetId, DomainIdentifier afterId, int limit) { return page(licensesForAsset(assetId), afterId, limit, SoftwareLicenseContract::id); }
        @Override public void insertLicense(SoftwareLicenseContract value) { licenses.put(value.id(), value); revision("license", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }
        @Override public void updateLicense(SoftwareLicenseContract value, long expectedVersion) { version(licenses.get(value.id()), expectedVersion, SoftwareLicenseContract::version); licenses.put(value.id(), value); revision("license", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }

        @Override public Optional<SupportProviderAuthorization> findSupportAuthorization(DomainIdentifier id) { return Optional.ofNullable(authorizations.get(id)); }
        @Override public List<SupportProviderAuthorization> supportAuthorizations(DomainIdentifier organizationId) {
            return authorizations.values().stream().filter(a -> a.organizationId().equals(organizationId)).toList();
        }
        @Override public Optional<SupportProviderAuthorization> findActiveSupportAuthorization(DomainIdentifier providerId, DomainIdentifier organizationId, LocalDate effectiveOn) {
            return authorizations.values().stream().filter(a -> a.providerPartnerId().equals(providerId) && a.organizationId().equals(organizationId) && a.selectableOn(effectiveOn)).findFirst();
        }
        @Override public void insertSupportAuthorization(SupportProviderAuthorization value) { authorizations.put(value.id(), value); }
        @Override public void updateSupportAuthorization(SupportProviderAuthorization value, long expectedVersion) { version(authorizations.get(value.id()), expectedVersion, SupportProviderAuthorization::version); authorizations.put(value.id(), value); }

        @Override public Optional<SupportCoverage> findSupportCoverage(DomainIdentifier id) { return Optional.ofNullable(coverages.get(id)); }
        @Override public List<SupportCoverage> supportCoveragesForAsset(DomainIdentifier assetId) { return byAsset(coverages.values(), assetId, SupportCoverage::assetId); }
        @Override public List<SupportCoverage> supportCoveragePage(DomainIdentifier assetId, DomainIdentifier afterId, int limit) { return page(supportCoveragesForAsset(assetId), afterId, limit, SupportCoverage::id); }
        @Override public List<SupportCoverage> supportCoveragesForAuthorization(DomainIdentifier authorizationId) { return coverages.values().stream().filter(c -> c.authorizationId().equals(authorizationId)).toList(); }
        @Override public void insertSupportCoverage(SupportCoverage value) { coverages.put(value.id(), value); revision("support_coverage", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }
        @Override public void updateSupportCoverage(SupportCoverage value, long expectedVersion) { version(coverages.get(value.id()), expectedVersion, SupportCoverage::version); coverages.put(value.id(), value); revision("support_coverage", value.id(), value.version(), value.status(), value.proofReference(), value.lastReason(), value.updatedAt(), value.updatedBy()); }

        @Override public Optional<WarrantyType> findWarrantyType(DomainIdentifier id) { return Optional.ofNullable(warrantyTypes.get(id)); }
        @Override public List<WarrantyType> warrantyTypes(boolean activeOnly) { return warrantyTypes.values().stream().filter(v -> !activeOnly || v.active()).toList(); }
        @Override public void insertWarrantyType(WarrantyType type) { warrantyTypes.put(type.id(), type); }
        @Override public List<Warranty> warrantiesDueBetween(LocalDate start, LocalDate end) { return warranties.values().stream().filter(w -> between(w.warrantyEndDate(), start, end) || between(w.manufacturerSupportEndDate(), start, end)).toList(); }
        @Override public List<SoftwareLicenseContract> licensesDueBetween(LocalDate start, LocalDate end) { return licenses.values().stream().filter(l -> (l.endsOn() != null && between(l.endsOn(), start, end)) || between(l.publisherSupportEndDate(), start, end)).toList(); }
        @Override public List<SupportCoverage> supportCoveragesDueBetween(LocalDate start, LocalDate end) { return coverages.values().stream().filter(c -> between(c.endsOn(), start, end)).toList(); }
        @Override public boolean reserveAlert(ComplianceAlert alert, LocalDate emittedOn) {
            return alertKeys.add(alert.kind() + ":" + alert.recordId() + ":" + alert.thresholdDays() + ":" + emittedOn);
        }
        @Override public List<ComplianceRevision> revisions(String recordType, DomainIdentifier recordId, long afterVersion, int limit) {
            return revisions.getOrDefault(recordType + ":" + recordId, List.of()).stream().filter(r -> r.version() > afterVersion).limit(limit).toList();
        }

        private void revision(String type, DomainIdentifier id, long version, ComplianceStatus status, String proof, String reason, Instant at, DomainIdentifier by) {
            revisions.computeIfAbsent(type + ":" + id, ignored -> new ArrayList<>()).add(
                    new ComplianceRevision(type, id, version, status, proof, reason, "{\"version\":" + version + "}", at, by));
        }
        private static boolean between(LocalDate date, LocalDate start, LocalDate end) { return !date.isBefore(start) && !date.isAfter(end); }
        private static <T> List<T> byAsset(Iterable<T> values, DomainIdentifier assetId, java.util.function.Function<T, DomainIdentifier> asset) {
            List<T> result = new ArrayList<>(); for (T value : values) if (asset.apply(value).equals(assetId)) result.add(value); return result;
        }
        private static <T> List<T> page(List<T> values, DomainIdentifier afterId, int limit, java.util.function.Function<T, DomainIdentifier> id) {
            return values.stream().filter(v -> afterId == null || id.apply(v).compareTo(afterId) > 0).sorted(Comparator.comparing(id)).limit((long) limit + 1L).toList();
        }
        private static <T> void version(T current, long expected, java.util.function.ToLongFunction<T> version) {
            if (current == null || version.applyAsLong(current) != expected) throw new ComplianceConflictException("VERSION_CONFLICT", "record version changed");
        }
    }

    private static void expectIllegal(ThrowingAction action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        catch (Exception error) { throw new AssertionError("unexpected exception", error); }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void expectCode(String code, ThrowingAction action) {
        try { action.run(); }
        catch (ComplianceConflictException error) { require(code.equals(error.code()), "unexpected compliance code: " + error.code()); return; }
        catch (Exception error) { throw new AssertionError("unexpected exception", error); }
        throw new AssertionError("expected ComplianceConflictException " + code);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
