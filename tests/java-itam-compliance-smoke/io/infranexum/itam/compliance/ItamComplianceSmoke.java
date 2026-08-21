package io.infranexum.itam.compliance;

import io.infranexum.core.contracts.DomainIdentifier;
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

/** Dependency-free PGM-07-E03 smoke for warranty, support and software-license governance. */
public final class ItamComplianceSmoke {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    private ItamComplianceSmoke() {}

    public static void main(String[] args) {
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

        WarrantyType type = service.createWarrantyType(null, "Manufacturer standard warranty",
                context(actor, correlation, "warranty-type-0001", "Governed warranty catalogue initialization"));
        require(type.active(), "warranty type must be active");
        require(type.code().startsWith("MANUFACTURER_STANDARD_WARRANTY_"), "warranty type code must be generated from display name while preserving the ITAM code contract");
        WarrantyType typeReplay = service.createWarrantyType(null, "Manufacturer standard warranty",
                context(actor, correlation, "warranty-type-0001", "Governed warranty catalogue initialization"));
        require(typeReplay.id().equals(type.id()), "warranty-type idempotent replay failed");

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

        System.out.println("java-itam-compliance-smoke: PASS");
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
        @Override public List<SupportProviderAuthorization> supportAuthorizations(DomainIdentifier organizationId) { return authorizations.values().stream().filter(a -> a.organizationId().equals(organizationId)).toList(); }
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

    private static void expectCode(String code, ThrowingAction action) {
        try { action.run(); }
        catch (ComplianceConflictException error) { require(code.equals(error.code()), "unexpected compliance code: " + error.code()); return; }
        catch (Exception error) { throw new AssertionError("unexpected exception", error); }
        throw new AssertionError("expected ComplianceConflictException " + code);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
