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
import io.infranexum.itam.asset.application.AssetCommandContext;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.application.CreateAssetCommand;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetCustodian;
import io.infranexum.itam.asset.domain.AssetCustodianKind;
import io.infranexum.itam.asset.domain.AssetLifecycleStatus;
import io.infranexum.itam.asset.domain.AssetNotFoundException;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-owned CLI for PGM-07-E02 using the same RBAC/ABAC and application service as HTTP. */
public final class ItamAssetCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_AUTHENTICATION = 3;
    public static final int EXIT_AUTHORIZATION = 4;
    public static final int EXIT_BUSINESS = 5;
    public static final int EXIT_INTERNAL = 70;

    private final LocalAuthenticationService authentication;
    private final RbacAuthorizationService authorization;
    private final PolicyDecisionService policyDecisions;
    private final IdentityAccessFeaturePolicy features;
    private final PlatformCapabilityService capabilities;
    private final AssetApplicationService assets;
    private final UuidV7Generator ids;
    private final JsonMapper json = JsonMapper.builder().build();

    public ItamAssetCli(
            LocalAuthenticationService authentication, RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions, IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities, AssetApplicationService assets, UuidV7Generator ids) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.policyDecisions = Objects.requireNonNull(policyDecisions, "policyDecisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** Executes one CLI command and returns a stable process exit code. */
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
        } catch (AssetConflictException failure) {
            err.println(failure.code() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (AssetNotFoundException | AssetQuotaException failure) {
            err.println(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            if (authenticated == null) {
                err.println("authentication failed"); err.flush(); return EXIT_AUTHENTICATION;
            }
            err.println("internal CLI failure: " + failure.getClass().getSimpleName()); err.flush(); return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try { authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session())); }
                catch (RuntimeException ignored) { /* Logout is best effort and must not replace the command status. */ }
            }
        }
    }

    private String execute(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        if (!"itam".equals(args.namespace()) || !"asset".equals(args.resource())) {
            throw new IllegalArgumentException("command must start with 'itam asset'");
        }
        return switch (args.operation()) {
            case "list" -> list(args, actor, correlation);
            case "show" -> show(args, actor, correlation);
            case "custody" -> custody(args, actor, correlation);
            case "acquire" -> acquire(args, actor, correlation);
            case "receive", "stock", "assign", "deploy", "transfer", "maintenance-start", "maintenance-return" ->
                    custodyTransition(args, actor, correlation);
            case "retire", "dispose" -> terminalTransition(args, actor, correlation);
            default -> throw new IllegalArgumentException("unknown ITAM Asset operation: " + args.operation());
        };
    }

    private String list(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        DomainIdentifier organization = args.has("organization-id") ? args.requiredId("organization-id") : null;
        require(actor, PermissionCodes.ITAM_ASSET_READ,
                organization == null ? AuthorizationScope.platform() : AuthorizationScope.organization(organization),
                correlation, "itam-asset", "collection", null);
        var page = assets.search(new AssetSearchCriteria(
                organization, args.has("asset-type") ? AssetType.parse(args.required("asset-type")) : null,
                args.has("status") ? status(args.required("status")) : null,
                args.has("rsot-object-id") ? args.requiredId("rsot-object-id") : null,
                args.has("cursor") ? args.requiredId("cursor") : null, args.limit()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.items().stream().map(ItamAssetCli::assetMap).toList());
        result.put("nextCursor", page.nextAfterId() == null ? null : page.nextAfterId().toString());
        return render(args, result);
    }

    private String show(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        Asset asset = assets.get(args.requiredId("id"));
        require(actor, PermissionCodes.ITAM_ASSET_READ, AuthorizationScope.organization(asset.owningOrganizationId()),
                correlation, "itam-asset", asset.id().toString(), null);
        return render(args, assetMap(asset));
    }

    private String custody(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        Asset asset = assets.get(args.requiredId("id"));
        require(actor, PermissionCodes.ITAM_ASSET_READ, AuthorizationScope.organization(asset.owningOrganizationId()),
                correlation, "itam-asset", asset.id().toString(), null);
        long after = Long.parseLong(args.optional("after-sequence", "0"));
        if (after < 0) throw new IllegalArgumentException("--after-sequence cannot be negative");
        List<Map<String, Object>> history = assets.custodyHistory(asset.id(), after, args.limit()).stream()
                .map(event -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("eventId", event.eventId().toString()); row.put("sequence", event.sequence());
                    row.put("eventType", event.eventType().name().toLowerCase(Locale.ROOT));
                    row.put("fromStatus", event.fromStatus() == null ? null : event.fromStatus().wireValue());
                    row.put("toStatus", event.toStatus().wireValue()); row.put("custodianKind", event.custodian().kind().wireValue());
                    row.put("custodianId", event.custodian().referenceId() == null ? null : event.custodian().referenceId().toString());
                    row.put("occurredAt", event.occurredAt().toString()); row.put("reason", event.reason());
                    row.put("evidenceReference", event.evidenceReference()); return row;
                }).toList();
        return render(args, history);
    }

    private String acquire(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        JsonNode root = readJson(args.required("input-file"));
        DomainIdentifier organization = DomainIdentifier.parse(requiredText(root, "owningOrganizationId"));
        String reason = root.get("reason") == null ? args.required("reason") : requiredText(root, "reason");
        require(actor, PermissionCodes.ITAM_ASSET_CREATE, AuthorizationScope.organization(organization),
                correlation, "itam-asset", "collection", reason);
        CreateAssetCommand command = new CreateAssetCommand(
                DomainIdentifier.parse(requiredText(root, "rsotObjectId")), requiredText(root, "assetType"), organization,
                optionalId(root, "owningSubdivisionId"), LocalDate.parse(requiredText(root, "acquisitionDate")),
                requiredDecimal(root, "acquisitionValue"), requiredText(root, "currencyCode"), optionalId(root, "acquiredFromPartnerId"));
        if (args.flag("dry-run")) {
            return render(args, Map.of("dryRun", true, "operation", "acquire ITAM asset", "rsotObjectId", command.rsotObjectId().toString()));
        }
        return render(args, assetMap(assets.create(command, context(args, actor, correlation, reason))));
    }

    private String custodyTransition(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        Asset asset = assets.get(args.requiredId("id"));
        String reason = args.required("reason");
        require(actor, PermissionCodes.ITAM_ASSET_UPDATE, AuthorizationScope.organization(asset.owningOrganizationId()),
                correlation, "itam-asset", asset.id().toString(), reason);
        AssetCustodian custodian = custodian(args);
        if (args.flag("dry-run")) return render(args, dryRun(args.operation(), asset));
        AssetCommandContext context = context(args, actor, correlation, reason);
        long version = args.version();
        Asset changed = switch (args.operation()) {
            case "receive" -> assets.receive(asset.id(), version, custodian, context);
            case "stock" -> assets.stock(asset.id(), version, custodian, context);
            case "assign" -> assets.assign(asset.id(), version, custodian, context);
            case "deploy" -> assets.deploy(asset.id(), version, custodian, context);
            case "transfer" -> assets.transfer(asset.id(), version, custodian, context);
            case "maintenance-start" -> assets.startMaintenance(asset.id(), version, custodian, context);
            case "maintenance-return" -> assets.returnFromMaintenance(asset.id(), version, custodian, context);
            default -> throw new IllegalStateException("unsupported custody transition");
        };
        return render(args, assetMap(changed));
    }

    private String terminalTransition(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        Asset asset = assets.get(args.requiredId("id"));
        String reason = args.required("reason");
        require(actor, PermissionCodes.ITAM_ASSET_UPDATE, AuthorizationScope.organization(asset.owningOrganizationId()),
                correlation, "itam-asset", asset.id().toString(), reason);
        if (args.flag("dry-run")) return render(args, dryRun(args.operation(), asset));
        AssetCommandContext context = context(args, actor, correlation, reason);
        Asset changed = "retire".equals(args.operation())
                ? assets.retire(asset.id(), args.version(), context)
                : assets.dispose(asset.id(), args.version(), context);
        return render(args, assetMap(changed));
    }

    private AssetCommandContext context(Arguments args, DomainIdentifier actor, DomainIdentifier correlation, String reason) {
        return new AssetCommandContext(actor, correlation, args.required("idempotency-key"), reason,
                args.optional("evidence-reference", null));
    }

    private AssetCustodian custodian(Arguments args) {
        AssetCustodianKind kind = AssetCustodianKind.parse(args.required("custodian-kind"));
        return kind == AssetCustodianKind.NONE ? AssetCustodian.none()
                : new AssetCustodian(kind, args.requiredId("custodian-id"));
    }

    private void require(
            DomainIdentifier actor, String permission, AuthorizationScope scope, DomainIdentifier correlation,
            String targetType, String targetId, String justificationValue) {
        AuthorizationDecision decision = authorization.decide(actor, permission, scope, correlation, targetType, targetId, "CLI");
        if (!decision.allowed()) throw new CliAuthorizationException(decision.explanation());
        if (!features.supportsAdvancedAuthorization()) return;
        boolean justification = justificationValue != null && validJustification(justificationValue);
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

    private JsonNode readJson(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--input-file must be an absolute path");
        try {
            JsonNode root = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) throw new IllegalArgumentException("--input-file root must be a JSON object");
            return root;
        } catch (IOException failure) {
            throw new IllegalArgumentException("--input-file is unreadable or invalid JSON", failure);
        }
    }

    private static Map<String, Object> assetMap(Asset asset) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", asset.id().toString()); result.put("rsotObjectId", asset.rsotObjectId().toString());
        result.put("assetType", asset.assetType().wireValue()); result.put("owningOrganizationId", asset.owningOrganizationId().toString());
        result.put("owningSubdivisionId", asset.owningSubdivisionId() == null ? null : asset.owningSubdivisionId().toString());
        result.put("acquisitionDate", asset.acquisitionDate().toString()); result.put("acquisitionValue", asset.acquisitionValue().amount());
        result.put("currencyCode", asset.acquisitionValue().currencyCode());
        result.put("acquiredFromPartnerId", asset.acquiredFromPartnerId() == null ? null : asset.acquiredFromPartnerId().toString());
        result.put("lifecycleStatus", asset.lifecycleStatus().wireValue()); result.put("custodianKind", asset.custodian().kind().wireValue());
        result.put("custodianId", asset.custodian().referenceId() == null ? null : asset.custodian().referenceId().toString());
        result.put("version", asset.version()); return result;
    }

    private static Map<String, Object> dryRun(String operation, Asset asset) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", true); result.put("operation", operation); result.put("assetId", asset.id().toString());
        result.put("currentVersion", asset.version()); return result;
    }

    private String render(Arguments args, Object value) {
        if (!args.json()) return value.toString();
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot render CLI JSON", failure); }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.textValue().strip();
    }

    private static java.math.BigDecimal requiredDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
        return value.decimalValue();
    }

    private static DomainIdentifier optionalId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException(field + " must be a UUIDv7 string");
        return DomainIdentifier.parse(value.textValue().strip());
    }

    private static AssetLifecycleStatus status(String value) {
        try { return AssetLifecycleStatus.valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unsupported --status", failure); }
    }

    private static boolean validJustification(String value) {
        String normalized = value.strip();
        return normalized.length() >= 8 && normalized.length() <= 500 && normalized.chars().noneMatch(Character::isISOControl);
    }

    private static char[] readSecret(String pathValue) {
        Path path = Path.of(pathValue);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--password-file must be an absolute path");
        byte[] bytes;
        try { bytes = Files.readAllBytes(path); }
        catch (IOException failure) { throw new IllegalArgumentException("--password-file is unreadable", failure); }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            while (decoded.hasRemaining() && Character.isWhitespace(decoded.get(decoded.limit() - 1))) decoded.limit(decoded.limit() - 1);
            if (!decoded.hasRemaining()) throw new IllegalArgumentException("--password-file is empty");
            char[] secret = new char[decoded.remaining()]; decoded.get(secret); return secret;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("--password-file must contain valid UTF-8", failure);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static String safe(String value) {
        if (value == null) return "request failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400);
    }

    private static boolean has(String[] values, String target) {
        for (String value : values) if (target.equals(value)) return true;
        return false;
    }

    private static String help() {
        return """
                InfraNexum ITAM Asset CLI
                  itam asset list --username USER --password-file ABS [--organization-id UUID] [--asset-type hardware|software] [--status STATUS] [--output json]
                  itam asset show|custody --id UUID --username USER --password-file ABS [--output json]
                  itam asset acquire --input-file ABS --idempotency-key KEY --username USER --password-file ABS [--reason TEXT] [--dry-run] [--output json]
                  itam asset receive|stock|assign|deploy|transfer|maintenance-start|maintenance-return --id UUID --version N --custodian-kind KIND --custodian-id UUID --reason TEXT --idempotency-key KEY --username USER --password-file ABS [--dry-run] [--output json]
                  itam asset retire --id UUID --version N --reason TEXT --idempotency-key KEY --username USER --password-file ABS [--dry-run] [--output json]
                  itam asset dispose --id UUID --version N --reason TEXT --evidence-reference REF --idempotency-key KEY --username USER --password-file ABS [--dry-run] [--output json]
                Passwords are accepted only through --password-file. Acquisition data is read from a JSON file.
                """;
    }

    private static final class CliAuthorizationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CliAuthorizationException(String message) { super(message); }
    }

    private record Arguments(String namespace, String resource, String operation, Map<String, String> values, Set<String> flags) {
        static Arguments parse(String[] input) {
            if (input.length < 3) throw new IllegalArgumentException("namespace, resource and operation are required");
            Map<String, String> values = new LinkedHashMap<>(); Set<String> flags = new LinkedHashSet<>();
            for (int index = 3; index < input.length; index++) {
                String token = input[index];
                if (!token.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + token);
                String key = token.substring(2);
                if (Set.of("dry-run", "json").contains(key)) { flags.add(key); continue; }
                if (index + 1 >= input.length || input[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException(token + " requires a value");
                }
                if (values.putIfAbsent(key, input[++index]) != null) throw new IllegalArgumentException("duplicate option: " + token);
            }
            if ("json".equalsIgnoreCase(values.get("output"))) flags.add("json");
            return new Arguments(input[0], input[1], input[2], Map.copyOf(values), Set.copyOf(flags));
        }
        boolean has(String key) { return values.containsKey(key) || flags.contains(key); }
        boolean flag(String key) { return flags.contains(key); }
        boolean json() { return flag("json"); }
        String required(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required");
            return value.strip();
        }
        String optional(String key, String fallback) {
            String value = values.get(key); return value == null || value.isBlank() ? fallback : value.strip();
        }
        DomainIdentifier requiredId(String key) { return DomainIdentifier.parse(required(key)); }
        int limit() {
            int value = Integer.parseInt(optional("limit", "50"));
            if (value < 1 || value > 200) throw new IllegalArgumentException("--limit must be between 1 and 200");
            return value;
        }
        long version() {
            long value = Long.parseLong(required("version"));
            if (value < 1) throw new IllegalArgumentException("--version must be positive");
            return value;
        }
    }
}
