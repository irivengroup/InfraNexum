package io.infranexum.server.itam.cli;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.application.AuthorizationDecision;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.identity.access.domain.PolicyEvaluationRequest;
import io.infranexum.identity.access.domain.PolicyObligation;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import io.infranexum.itam.compliance.application.ComplianceCommandContext;
import io.infranexum.itam.compliance.application.CreateLicenseCommand;
import io.infranexum.itam.compliance.application.CreateSupportAuthorizationCommand;
import io.infranexum.itam.compliance.application.CreateSupportCoverageCommand;
import io.infranexum.itam.compliance.application.CreateWarrantyCommand;
import io.infranexum.itam.compliance.domain.ComplianceConflictException;
import io.infranexum.itam.compliance.domain.ComplianceNotFoundException;
import io.infranexum.itam.compliance.domain.SoftwareLicenseContract;
import io.infranexum.itam.compliance.domain.SupportCoverage;
import io.infranexum.itam.compliance.domain.SupportProviderAuthorization;
import io.infranexum.itam.compliance.domain.Warranty;
import io.infranexum.itam.compliance.domain.WarrantyType;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-owned CLI for PGM-07-E03 using the same RBAC/ABAC and application services as HTTP. */
public final class ItamComplianceCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_AUTHENTICATION = 3;
    public static final int EXIT_AUTHORIZATION = 4;
    public static final int EXIT_BUSINESS = 5;
    public static final int EXIT_INTERNAL = 70;

    private static final Set<String> RAW_LICENSE_KEY_FIELDS = Set.of(
            "licenseKey", "license_key", "productKey", "product_key", "serialKey", "serial_key");

    private final LocalAuthenticationService authentication;
    private final RbacAuthorizationService authorization;
    private final PolicyDecisionService policyDecisions;
    private final IdentityAccessFeaturePolicy features;
    private final PlatformCapabilityService capabilities;
    private final ComplianceApplicationService compliance;
    private final AssetApplicationService assets;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public ItamComplianceCli(
            LocalAuthenticationService authentication, RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions, IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities, ComplianceApplicationService compliance,
            AssetApplicationService assets, UuidV7Generator ids, @Qualifier("platformClock") Clock clock) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.policyDecisions = Objects.requireNonNull(policyDecisions, "policyDecisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.compliance = Objects.requireNonNull(compliance, "compliance");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Executes one contractual-governance CLI command and returns a stable process exit code. */
    public int run(String[] arguments, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(arguments, "arguments"); Objects.requireNonNull(out, "out"); Objects.requireNonNull(err, "err");
        if (arguments.length == 0 || has(arguments, "--help") || has(arguments, "-h")) {
            out.print(help()); out.flush(); return EXIT_OK;
        }
        AuthenticatedSession authenticated = null;
        try {
            Arguments args = Arguments.parse(arguments);
            char[] password = readSecret(args.required("password-file"));
            try { authenticated = authentication.authenticate(args.required("username"), password); }
            finally { Arrays.fill(password, '\0'); }
            String rendered = execute(args, authenticated.account().id(), ids.next());
            if (!rendered.isEmpty()) out.println(rendered);
            out.flush(); return EXIT_OK;
        } catch (CliAuthorizationException failure) {
            err.println("authorization denied: " + safe(failure.getMessage())); err.flush(); return EXIT_AUTHORIZATION;
        } catch (IllegalArgumentException failure) {
            err.println("usage error: " + safe(failure.getMessage())); err.flush(); return EXIT_USAGE;
        } catch (ComplianceConflictException | ComplianceNotFoundException failure) {
            err.println(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            if (authenticated == null) { err.println("authentication failed"); err.flush(); return EXIT_AUTHENTICATION; }
            err.println("internal CLI failure: " + failure.getClass().getSimpleName()); err.flush(); return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try { authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session())); }
                catch (RuntimeException ignored) { /* Logout must not replace the command exit status. */ }
            }
        }
    }

    private String execute(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        if (!"itam".equals(args.namespace())) throw new IllegalArgumentException("command must start with 'itam'");
        return switch (args.resource()) {
            case "warranty" -> warranty(args, actor, correlation);
            case "license" -> license(args, actor, correlation);
            case "support-coverage" -> supportCoverage(args, actor, correlation);
            case "support-authorization" -> supportAuthorization(args, actor, correlation);
            case "warranty-type" -> warrantyType(args, actor, correlation);
            case "compliance" -> complianceRead(args, actor, correlation);
            default -> throw new IllegalArgumentException("unknown ITAM compliance resource: " + args.resource());
        };
    }

    private String warranty(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        return switch (args.operation()) {
            case "list" -> {
                Asset asset = asset(args.requiredId("asset-id")); require(actor, PermissionCodes.ITAM_WARRANTY_READ, asset, correlation, "warranty", "collection", null);
                var page = compliance.warrantyPage(asset.id(), args.optionalId("cursor"), args.limit());
                yield render(args, pageMap(page.items().stream().map(ItamComplianceCli::warrantyMap).toList(), page.nextAfterId()));
            }
            case "create" -> {
                Asset asset = asset(args.requiredId("asset-id")); JsonNode input = readJson(args.required("input-file")); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_WARRANTY_MANAGE, asset, correlation, "warranty", "collection", reason);
                CreateWarrantyCommand command = warrantyCommand(asset.id(), input);
                if (args.flag("dry-run")) yield render(args, dryRun("warranty-create", asset.id()));
                yield render(args, warrantyMap(compliance.createWarranty(command, context(args, actor, correlation, reason))));
            }
            case "revise" -> {
                Warranty current = compliance.getWarranty(args.requiredId("id")); Asset asset = asset(current.assetId()); JsonNode input = readJson(args.required("input-file")); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_WARRANTY_MANAGE, asset, correlation, "warranty", current.id().toString(), reason);
                if (args.flag("dry-run")) yield render(args, dryRun("warranty-revise", current.id()));
                yield render(args, warrantyMap(compliance.reviseWarranty(current.id(), args.version(), warrantyCommand(asset.id(), input), context(args, actor, correlation, reason))));
            }
            case "activate", "expire" -> warrantyLifecycle(args, actor, correlation);
            default -> throw new IllegalArgumentException("unknown ITAM warranty operation: " + args.operation());
        };
    }

    private String warrantyLifecycle(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        Warranty current = compliance.getWarranty(args.requiredId("id")); Asset asset = asset(current.assetId()); String reason = args.required("reason");
        require(actor, PermissionCodes.ITAM_WARRANTY_MANAGE, asset, correlation, "warranty", current.id().toString(), reason);
        if (args.flag("dry-run")) return render(args, dryRun("warranty-" + args.operation(), current.id()));
        ComplianceCommandContext context = context(args, actor, correlation, reason);
        Warranty changed = "activate".equals(args.operation())
                ? compliance.activateWarranty(current.id(), args.version(), context)
                : compliance.expireWarranty(current.id(), args.version(), context);
        return render(args, warrantyMap(changed));
    }

    private String license(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        return switch (args.operation()) {
            case "list" -> {
                Asset asset = asset(args.requiredId("asset-id")); require(actor, PermissionCodes.ITAM_LICENSE_READ, asset, correlation, "license", "collection", null);
                var page = compliance.licensePage(asset.id(), args.optionalId("cursor"), args.limit());
                yield render(args, pageMap(page.items().stream().map(ItamComplianceCli::licenseMap).toList(), page.nextAfterId()));
            }
            case "create" -> {
                Asset asset = asset(args.requiredId("asset-id")); JsonNode input = readJson(args.required("input-file")); rejectRawLicenseKeys(input); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_LICENSE_MANAGE, asset, correlation, "license", "collection", reason);
                CreateLicenseCommand command = licenseCommand(asset.id(), input);
                if (args.flag("dry-run")) yield render(args, dryRun("license-create", asset.id()));
                yield render(args, licenseMap(compliance.createLicense(command, context(args, actor, correlation, reason))));
            }
            case "revise" -> {
                SoftwareLicenseContract current = compliance.getLicense(args.requiredId("id")); Asset asset = asset(current.assetId()); JsonNode input = readJson(args.required("input-file")); rejectRawLicenseKeys(input); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_LICENSE_MANAGE, asset, correlation, "license", current.id().toString(), reason);
                if (args.flag("dry-run")) yield render(args, dryRun("license-revise", current.id()));
                yield render(args, licenseMap(compliance.reviseLicense(current.id(), args.version(), licenseCommand(asset.id(), input), context(args, actor, correlation, reason))));
            }
            case "activate", "expire" -> licenseLifecycle(args, actor, correlation);
            default -> throw new IllegalArgumentException("unknown ITAM license operation: " + args.operation());
        };
    }

    private String licenseLifecycle(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        SoftwareLicenseContract current = compliance.getLicense(args.requiredId("id")); Asset asset = asset(current.assetId()); String reason = args.required("reason");
        require(actor, PermissionCodes.ITAM_LICENSE_MANAGE, asset, correlation, "license", current.id().toString(), reason);
        if (args.flag("dry-run")) return render(args, dryRun("license-" + args.operation(), current.id()));
        ComplianceCommandContext context = context(args, actor, correlation, reason);
        SoftwareLicenseContract changed = "activate".equals(args.operation())
                ? compliance.activateLicense(current.id(), args.version(), context)
                : compliance.expireLicense(current.id(), args.version(), context);
        return render(args, licenseMap(changed));
    }

    private String supportCoverage(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        return switch (args.operation()) {
            case "list" -> {
                Asset asset = asset(args.requiredId("asset-id")); require(actor, PermissionCodes.ITAM_SUPPORT_COVERAGE_READ, asset, correlation, "support-coverage", "collection", null);
                var page = compliance.supportCoveragePage(asset.id(), args.optionalId("cursor"), args.limit());
                yield render(args, pageMap(page.items().stream().map(ItamComplianceCli::coverageMap).toList(), page.nextAfterId()));
            }
            case "create" -> {
                Asset asset = asset(args.requiredId("asset-id")); JsonNode input = readJson(args.required("input-file")); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE, asset, correlation, "support-coverage", "collection", reason);
                CreateSupportCoverageCommand command = coverageCommand(asset.id(), input);
                if (args.flag("dry-run")) yield render(args, dryRun("support-coverage-create", asset.id()));
                yield render(args, coverageMap(compliance.createSupportCoverage(command, context(args, actor, correlation, reason))));
            }
            case "revise" -> {
                SupportCoverage current = compliance.getSupportCoverage(args.requiredId("id")); Asset asset = asset(current.assetId()); JsonNode input = readJson(args.required("input-file")); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE, asset, correlation, "support-coverage", current.id().toString(), reason);
                if (args.flag("dry-run")) yield render(args, dryRun("support-coverage-revise", current.id()));
                yield render(args, coverageMap(compliance.reviseSupportCoverage(current.id(), args.version(), coverageCommand(asset.id(), input), context(args, actor, correlation, reason))));
            }
            case "activate", "expire" -> coverageLifecycle(args, actor, correlation);
            default -> throw new IllegalArgumentException("unknown ITAM support-coverage operation: " + args.operation());
        };
    }

    private String coverageLifecycle(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        SupportCoverage current = compliance.getSupportCoverage(args.requiredId("id")); Asset asset = asset(current.assetId()); String reason = args.required("reason");
        require(actor, PermissionCodes.ITAM_SUPPORT_COVERAGE_MANAGE, asset, correlation, "support-coverage", current.id().toString(), reason);
        if (args.flag("dry-run")) return render(args, dryRun("support-coverage-" + args.operation(), current.id()));
        ComplianceCommandContext context = context(args, actor, correlation, reason);
        SupportCoverage changed = "activate".equals(args.operation())
                ? compliance.activateSupportCoverage(current.id(), args.version(), context)
                : compliance.expireSupportCoverage(current.id(), args.version(), context);
        return render(args, coverageMap(changed));
    }

    private String supportAuthorization(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        return switch (args.operation()) {
            case "show" -> {
                SupportProviderAuthorization current = compliance.getSupportAuthorization(args.requiredId("id"));
                require(actor, PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE, current.organizationId(), correlation, "support-authorization", current.id().toString(), null);
                yield render(args, authorizationMap(current));
            }
            case "create" -> {
                JsonNode input = readJson(args.required("input-file")); DomainIdentifier organization = DomainIdentifier.parse(requiredText(input, "organizationId")); String reason = reason(args, input);
                require(actor, PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE, organization, correlation, "support-authorization", "collection", reason);
                if (args.flag("dry-run")) yield render(args, dryRun("support-authorization-create", organization));
                yield render(args, authorizationMap(compliance.createSupportAuthorization(authCommand(input), context(args, actor, correlation, reason))));
            }
            case "activate", "suspend" -> {
                SupportProviderAuthorization current = compliance.getSupportAuthorization(args.requiredId("id")); String reason = args.required("reason");
                require(actor, PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE, current.organizationId(), correlation, "support-authorization", current.id().toString(), reason);
                if (args.flag("dry-run")) yield render(args, dryRun("support-authorization-" + args.operation(), current.id()));
                ComplianceCommandContext context = context(args, actor, correlation, reason);
                SupportProviderAuthorization changed = "activate".equals(args.operation())
                        ? compliance.activateSupportAuthorization(current.id(), args.version(), context)
                        : compliance.suspendSupportAuthorization(current.id(), args.version(), context);
                yield render(args, authorizationMap(changed));
            }
            default -> throw new IllegalArgumentException("unknown support-authorization operation: " + args.operation());
        };
    }

    private String warrantyType(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        DomainIdentifier organization = args.requiredId("organization-id");
        return switch (args.operation()) {
            case "list" -> {
                require(actor, PermissionCodes.ITAM_WARRANTY_READ, organization, correlation, "warranty-type", "collection", null);
                yield render(args, compliance.warrantyTypes().stream().map(ItamComplianceCli::warrantyTypeMap).toList());
            }
            case "create" -> {
                String reason = args.required("reason"); require(actor, PermissionCodes.ITAM_SUPPORT_CATALOG_MANAGE, organization, correlation, "warranty-type", "collection", reason);
                if (args.flag("dry-run")) yield render(args, dryRun("warranty-type-create", organization));
                WarrantyType type = compliance.createWarrantyType(args.optional("code", null), args.required("display-name"), context(args, actor, correlation, reason));
                yield render(args, warrantyTypeMap(type));
            }
            default -> throw new IllegalArgumentException("unknown warranty-type operation: " + args.operation());
        };
    }

    private String complianceRead(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        return switch (args.operation()) {
            case "alerts" -> {
                Asset asset = asset(args.requiredId("asset-id")); require(actor, PermissionCodes.ITAM_AUDIT_READ, asset, correlation, "compliance-alert", asset.id().toString(), null);
                LocalDate asOf = args.has("as-of") ? LocalDate.parse(args.required("as-of")) : LocalDate.now(clock);
                int horizon = args.integer("horizon-days", 180, 1, 3650);
                yield render(args, compliance.upcomingAlerts(asset.id(), asOf, horizon));
            }
            case "history" -> {
                String type = normalizedRecordType(args.required("record-type")); DomainIdentifier recordId = args.requiredId("id"); Asset asset = switch (type) {
                    case "warranty" -> asset(compliance.getWarranty(recordId).assetId());
                    case "license" -> asset(compliance.getLicense(recordId).assetId());
                    case "support_coverage" -> asset(compliance.getSupportCoverage(recordId).assetId());
                    default -> throw new IllegalStateException("record type normalization failed");
                };
                require(actor, PermissionCodes.ITAM_AUDIT_READ, asset, correlation, "compliance-history", recordId.toString(), null);
                yield render(args, compliance.history(type, recordId, args.longValue("after-version", 0, 0), args.limit()));
            }
            default -> throw new IllegalArgumentException("unknown compliance read operation: " + args.operation());
        };
    }

    private void require(DomainIdentifier actor, String permission, Asset asset, DomainIdentifier correlation, String targetType, String targetId, String reason) {
        require(actor, permission, asset.owningOrganizationId(), correlation, targetType, targetId, reason);
    }
    private void require(DomainIdentifier actor, String permission, DomainIdentifier organization, DomainIdentifier correlation, String targetType, String targetId, String reason) {
        AuthorizationScope scope = AuthorizationScope.organization(organization);
        AuthorizationDecision decision = authorization.decide(actor, permission, scope, correlation, targetType, targetId, "CLI");
        if (!decision.allowed()) throw new CliAuthorizationException(decision.explanation());
        if (!features.supportsAdvancedAuthorization()) return;
        boolean justification = reason != null && validJustification(reason);
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        PolicyEvaluationRequest request = new PolicyEvaluationRequest(actor, permission, targetType, targetId, scope,
                Map.of("channel", "CLI", "justification_present", Boolean.toString(justification)),
                "LOCAL_SESSION", capabilityVersion, null, true);
        var advanced = policyDecisions.decide(request, correlation, "CLI");
        if (!advanced.permitted()) throw new CliAuthorizationException(advanced.reasonCode());
        for (PolicyObligation obligation : advanced.obligations()) {
            if (obligation == PolicyObligation.REQUIRE_JUSTIFICATION && justification) continue;
            throw new CliAuthorizationException("required authorization obligation is not satisfied: " + obligation.name());
        }
    }

    private ComplianceCommandContext context(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, String reason) {
        return new ComplianceCommandContext(actor, correlation, args.required("idempotency-key"), reason);
    }
    private Asset asset(DomainIdentifier id) { return assets.get(id); }

    private static CreateWarrantyCommand warrantyCommand(DomainIdentifier asset, JsonNode n) {
        return new CreateWarrantyCommand(asset, id(n, "manufacturerPartnerId"), id(n, "warrantyTypeId"), requiredText(n, "coverageLevel"),
                date(n, "warrantyStartDate"), date(n, "warrantyEndDate"), date(n, "manufacturerSupportEndDate"), optionalText(n, "contractOrCertificateNumber"),
                requiredText(n, "proofReference"), requiredText(n, "source"));
    }
    private static CreateLicenseCommand licenseCommand(DomainIdentifier asset, JsonNode n) {
        return new CreateLicenseCommand(asset, id(n, "publisherPartnerId"), requiredText(n, "contractNumber"), requiredText(n, "licenseModel"),
                requiredText(n, "usageRights"), positiveLong(n, "entitlementQuantity"), date(n, "startsOn"), optionalDate(n, "endsOn"),
                date(n, "publisherSupportEndDate"), requiredText(n, "proofReference"), requiredText(n, "source"));
    }
    private static CreateSupportCoverageCommand coverageCommand(DomainIdentifier asset, JsonNode n) {
        return new CreateSupportCoverageCommand(asset, id(n, "providerPartnerId"), id(n, "authorizationId"), optionalText(n, "contractReference"),
                requiredText(n, "coverageType"), requiredText(n, "serviceLevel"), date(n, "startsOn"), date(n, "endsOn"), requiredText(n, "proofReference"));
    }
    private static CreateSupportAuthorizationCommand authCommand(JsonNode n) {
        return new CreateSupportAuthorizationCommand(id(n, "providerPartnerId"), id(n, "organizationId"), idSet(n, "supportedManufacturerIds", true),
                textSet(n, "supportedObjectTypes", true), idSet(n, "subdivisionScopes", false), requiredText(n, "serviceHours"), requiredText(n, "timeZoneId"),
                textSet(n, "serviceLevels", true), textSet(n, "escalationContactTypes", true), date(n, "validFrom"), optionalDate(n, "validUntil"));
    }

    private JsonNode readJson(String pathValue) {
        Path path = Path.of(pathValue); if (!path.isAbsolute()) throw new IllegalArgumentException("--input-file must be an absolute path");
        try { JsonNode root = json.readTree(Files.readString(path, StandardCharsets.UTF_8)); if (root == null || !root.isObject()) throw new IllegalArgumentException("--input-file root must be a JSON object"); return root; }
        catch (IOException failure) { throw new IllegalArgumentException("--input-file is unreadable or invalid JSON", failure); }
    }
    private static void rejectRawLicenseKeys(JsonNode root) { for (String field : RAW_LICENSE_KEY_FIELDS) if (root.get(field) != null) throw new IllegalArgumentException("raw software license keys are not accepted until Secret Service is available"); }
    private static String reason(Arguments args, JsonNode input) { return input.get("reason") == null ? args.required("reason") : requiredText(input, "reason"); }
    private static String requiredText(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isTextual() || value.textValue() == null || value.textValue().isBlank()) throw new IllegalArgumentException(field + " is required"); return value.textValue().strip(); }
    private static String optionalText(JsonNode node, String field) { JsonNode value = node.get(field); return value == null || value.isNull() ? null : requiredText(node, field); }
    private static DomainIdentifier id(JsonNode node, String field) { return DomainIdentifier.parse(requiredText(node, field)); }
    private static LocalDate date(JsonNode node, String field) { return LocalDate.parse(requiredText(node, field)); }
    private static LocalDate optionalDate(JsonNode node, String field) { String value = optionalText(node, field); return value == null ? null : LocalDate.parse(value); }
    private static long positiveLong(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isIntegralNumber() || value.longValue() < 1) throw new IllegalArgumentException(field + " must be a positive integer"); return value.longValue(); }
    private static Set<String> textSet(JsonNode node, String field, boolean required) { JsonNode value = node.get(field); if (value == null || value.isNull()) { if (required) throw new IllegalArgumentException(field + " is required"); return Set.of(); } if (!value.isArray() || (required && value.size() == 0)) throw new IllegalArgumentException(field + " must be " + (required ? "a non-empty " : "an ") + "array"); Set<String> result = new LinkedHashSet<>(); for (JsonNode child : value) { if (!child.isTextual() || child.textValue().isBlank()) throw new IllegalArgumentException(field + " values must be non-empty strings"); result.add(child.textValue().strip()); } return Set.copyOf(result); }
    private static Set<DomainIdentifier> idSet(JsonNode node, String field, boolean required) { Set<DomainIdentifier> result = new LinkedHashSet<>(); for (String value : textSet(node, field, required)) result.add(DomainIdentifier.parse(value)); return Set.copyOf(result); }

    private static Map<String, Object> warrantyMap(Warranty w) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.id().toString());m.put("assetId",w.assetId().toString());m.put("manufacturerPartnerId",w.manufacturerPartnerId().toString());m.put("warrantyTypeId",w.warrantyTypeId().toString());m.put("warrantyEndDate",w.warrantyEndDate().toString());m.put("manufacturerSupportEndDate",w.manufacturerSupportEndDate().toString());m.put("status",w.status().wireValue());m.put("version",w.version());return m; }
    private static Map<String, Object> licenseMap(SoftwareLicenseContract l) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",l.id().toString());m.put("assetId",l.assetId().toString());m.put("publisherPartnerId",l.publisherPartnerId().toString());m.put("contractNumber",l.contractNumber());m.put("licenseModel",l.licenseModel());m.put("endsOn",l.endsOn()==null?null:l.endsOn().toString());m.put("publisherSupportEndDate",l.publisherSupportEndDate().toString());m.put("status",l.status().wireValue());m.put("version",l.version());return m; }
    private static Map<String, Object> coverageMap(SupportCoverage c) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",c.id().toString());m.put("assetId",c.assetId().toString());m.put("providerPartnerId",c.providerPartnerId().toString());m.put("authorizationId",c.authorizationId().toString());m.put("serviceLevel",c.serviceLevel());m.put("startsOn",c.startsOn().toString());m.put("endsOn",c.endsOn().toString());m.put("status",c.status().wireValue());m.put("version",c.version());return m; }
    private static Map<String, Object> authorizationMap(SupportProviderAuthorization a) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",a.id().toString());m.put("providerPartnerId",a.providerPartnerId().toString());m.put("organizationId",a.organizationId().toString());m.put("supportedManufacturerIds",a.supportedManufacturerIds().stream().map(Object::toString).toList());m.put("supportedObjectTypes",a.supportedObjectTypes());m.put("serviceLevels",a.serviceLevels());m.put("status",a.status().wireValue());m.put("version",a.version());return m; }
    private static Map<String, Object> warrantyTypeMap(WarrantyType t) { return Map.of("id",t.id().toString(),"code",t.code(),"displayName",t.displayName(),"active",t.active()); }
    private static Map<String, Object> pageMap(List<Map<String,Object>> items, DomainIdentifier next) { Map<String,Object> m=new LinkedHashMap<>();m.put("items",items);m.put("nextCursor",next==null?null:next.toString());return m; }
    private static Map<String, Object> dryRun(String operation, DomainIdentifier target) { return Map.of("dryRun",true,"operation",operation,"targetId",target.toString()); }

    private String render(Arguments args, Object value) { if (!args.json()) return value.toString(); try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException("cannot render CLI JSON", failure); } }
    private static String normalizedRecordType(String value) { return switch (value.strip().toLowerCase()) { case "warranty","warranties" -> "warranty"; case "license","licenses" -> "license"; case "support-coverage","support-coverages","support_coverage" -> "support_coverage"; default -> throw new IllegalArgumentException("unsupported --record-type"); }; }
    private static boolean validJustification(String value) { String n=value.strip(); return n.length()>=8&&n.length()<=500&&n.chars().noneMatch(Character::isISOControl); }
    private static char[] readSecret(String pathValue) { Path path=Path.of(pathValue);if(!path.isAbsolute())throw new IllegalArgumentException("--password-file must be an absolute path");byte[] bytes;try{bytes=Files.readAllBytes(path);}catch(IOException failure){throw new IllegalArgumentException("--password-file is unreadable",failure);}try{CharBuffer decoded=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));while(decoded.hasRemaining()&&Character.isWhitespace(decoded.get(decoded.limit()-1)))decoded.limit(decoded.limit()-1);if(!decoded.hasRemaining())throw new IllegalArgumentException("--password-file is empty");char[] secret=new char[decoded.remaining()];decoded.get(secret);return secret;}catch(CharacterCodingException failure){throw new IllegalArgumentException("--password-file must contain valid UTF-8",failure);}finally{Arrays.fill(bytes,(byte)0);}}
    private static String safe(String value){if(value==null)return "request failed";String n=value.replaceAll("[\\r\\n\\t]+"," ").strip();return n.length()<=400?n:n.substring(0,400);}
    private static boolean has(String[] values,String target){for(String value:values)if(target.equals(value))return true;return false;}

    private static String help(){return """
            InfraNexum ITAM Compliance CLI
              itam warranty list|create|revise|activate|expire ...
              itam license list|create|revise|activate|expire ...
              itam support-coverage list|create|revise|activate|expire ...
              itam support-authorization show|create|activate|suspend ...
              itam warranty-type list|create --organization-id UUID ...
              itam compliance alerts --asset-id UUID [--as-of YYYY-MM-DD] [--horizon-days N] ...
              itam compliance history --record-type warranty|license|support-coverage --id UUID ...
            Mutations require --reason and --idempotency-key unless --dry-run is used. Create/revise contracts use --input-file ABS.
            Passwords are accepted only through --password-file. Raw software license keys are not accepted.
            """;}

    private static final class CliAuthorizationException extends RuntimeException { private static final long serialVersionUID=1L; CliAuthorizationException(String message){super(message);} }
    private record Arguments(String namespace,String resource,String operation,Map<String,String> values,Set<String> flags){
        static Arguments parse(String[] input){if(input.length<3)throw new IllegalArgumentException("namespace, resource and operation are required");Map<String,String> values=new LinkedHashMap<>();Set<String> flags=new LinkedHashSet<>();for(int index=3;index<input.length;index++){String token=input[index];if(!token.startsWith("--"))throw new IllegalArgumentException("unexpected argument: "+token);String key=token.substring(2);if(Set.of("dry-run","json").contains(key)){flags.add(key);continue;}if(index+1>=input.length||input[index+1].startsWith("--"))throw new IllegalArgumentException(token+" requires a value");if(values.putIfAbsent(key,input[++index])!=null)throw new IllegalArgumentException("duplicate option: "+token);}if("json".equalsIgnoreCase(values.get("output")))flags.add("json");return new Arguments(input[0],input[1],input[2],Map.copyOf(values),Set.copyOf(flags));}
        boolean has(String key){return values.containsKey(key)||flags.contains(key);}boolean flag(String key){return flags.contains(key);}boolean json(){return flag("json");}
        String required(String key){String value=values.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("--"+key+" is required");return value.strip();}
        String optional(String key,String fallback){String value=values.get(key);return value==null||value.isBlank()?fallback:value.strip();}
        DomainIdentifier requiredId(String key){return DomainIdentifier.parse(required(key));} DomainIdentifier optionalId(String key){return has(key)?requiredId(key):null;}
        int limit(){return integer("limit",50,1,200);} int integer(String key,int fallback,int min,int max){int value=Integer.parseInt(optional(key,Integer.toString(fallback)));if(value<min||value>max)throw new IllegalArgumentException("--"+key+" must be between "+min+" and "+max);return value;}
        long longValue(String key,long fallback,long min){long value=Long.parseLong(optional(key,Long.toString(fallback)));if(value<min)throw new IllegalArgumentException("--"+key+" must be at least "+min);return value;} long version(){return longValue("version",0,1);}
    }
}
