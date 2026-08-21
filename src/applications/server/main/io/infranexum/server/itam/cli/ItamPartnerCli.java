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
import io.infranexum.itam.partner.application.CreatePartnerCommand;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.itam.partner.application.PartnerCommandContext;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.Partner;
import io.infranexum.itam.partner.domain.PartnerAccreditation;
import io.infranexum.itam.partner.domain.PartnerAuthorizationStatus;
import io.infranexum.itam.partner.domain.PartnerConflictException;
import io.infranexum.itam.partner.domain.PartnerContact;
import io.infranexum.itam.partner.domain.PartnerExternalId;
import io.infranexum.itam.partner.domain.PartnerNotFoundException;
import io.infranexum.itam.partner.domain.PartnerQuotaException;
import io.infranexum.itam.partner.domain.PartnerRole;
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
import java.util.ArrayList;
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

/** Server-owned CLI exposing PGM-07-E01 with the same RBAC/ABAC and domain services as HTTP. */
public final class ItamPartnerCli {
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
    private final PartnerApplicationService partners;
    private final UuidV7Generator ids;
    private final JsonMapper json = JsonMapper.builder().build();

    public ItamPartnerCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            PartnerApplicationService partners,
            UuidV7Generator ids) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.policyDecisions = Objects.requireNonNull(policyDecisions, "policyDecisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.partners = Objects.requireNonNull(partners, "partners");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** Executes one command and returns a stable process exit code. */
    public int run(String[] arguments, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (arguments.length == 0 || has(arguments, "--help") || has(arguments, "-h")) {
            out.print(help()); out.flush(); return EXIT_OK;
        }
        AuthenticatedSession authenticated = null;
        try {
            Arguments args = Arguments.parse(arguments);
            char[] password = readSecret(args.required("password-file"));
            try {
                authenticated = authentication.authenticate(args.required("username"), password);
            } finally {
                Arrays.fill(password, '\0');
            }
            DomainIdentifier actor = authenticated.account().id();
            DomainIdentifier correlation = ids.next();
            String rendered = execute(args, actor, correlation);
            if (!rendered.isEmpty()) out.println(rendered);
            out.flush();
            return EXIT_OK;
        } catch (CliAuthorizationException failure) {
            err.println("authorization denied: " + safe(failure.getMessage())); err.flush(); return EXIT_AUTHORIZATION;
        } catch (IllegalArgumentException failure) {
            err.println("usage error: " + safe(failure.getMessage())); err.flush(); return EXIT_USAGE;
        } catch (PartnerConflictException failure) {
            err.println(failure.code() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (PartnerNotFoundException | PartnerQuotaException failure) {
            err.println(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            if (authenticated == null) {
                err.println("authentication failed"); err.flush(); return EXIT_AUTHENTICATION;
            }
            err.println("internal CLI failure: " + failure.getClass().getSimpleName()); err.flush(); return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try { authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session())); }
                catch (RuntimeException ignored) { /* Best-effort cleanup must not replace the command result. */ }
            }
        }
    }

    private String execute(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        if (!"itam".equals(args.namespace()) || !"partner".equals(args.resource())) {
            throw new IllegalArgumentException("command must start with 'itam partner'");
        }
        return switch (args.operation()) {
            case "list" -> list(args, actor, correlation);
            case "create" -> create(args, actor, correlation);
            case "submit-approval" -> transition(args, actor, correlation, PermissionCodes.ITAM_PARTNER_APPROVE, "submit-approval");
            case "authorize" -> transition(args, actor, correlation, PermissionCodes.ITAM_PARTNER_APPROVE, "authorize");
            case "suspend" -> transition(args, actor, correlation, PermissionCodes.ITAM_PARTNER_SUSPEND, "suspend");
            default -> throw new IllegalArgumentException("unknown ITAM Partner operation: " + args.operation());
        };
    }

    private String list(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        DomainIdentifier organization = args.has("organization-id") ? args.requiredId("organization-id") : null;
        AuthorizationScope scope = organization == null ? AuthorizationScope.platform() : AuthorizationScope.organization(organization);
        require(actor, PermissionCodes.ITAM_PARTNER_READ, scope, correlation, "itam-partner", "collection", null);
        var page = partners.search(new PartnerSearchCriteria(
                organization,
                args.has("role") ? PartnerRole.parse(args.required("role")) : null,
                args.has("status") ? PartnerAuthorizationStatus.parse(args.required("status")) : null,
                args.optional("country-code", null), args.optional("accreditation", null),
                args.has("effective-on") ? LocalDate.parse(args.required("effective-on")) : null,
                args.has("cursor") ? args.requiredId("cursor") : null, args.limit()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.items().stream().map(ItamPartnerCli::partnerMap).toList());
        result.put("nextCursor", page.nextCursor() == null ? null : page.nextCursor().toString());
        return render(args, result);
    }

    private String create(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        JsonNode root = readJson(args.required("input-file"));
        DomainIdentifier organization = DomainIdentifier.parse(requiredText(root, "governingOrganizationId"));
        String reason = root.get("reason") == null ? args.required("reason") : requiredText(root, "reason");
        require(actor, PermissionCodes.ITAM_PARTNER_CREATE, AuthorizationScope.organization(organization),
                correlation, "itam-partner", "collection", reason);
        CreatePartnerCommand command = command(root, organization);
        if (args.flag("dry-run")) return render(args, Map.of("dryRun", true, "operation", "create ITAM Partner", "code", command.code()));
        Partner result = partners.create(command, context(args, actor, correlation, reason));
        return render(args, partnerMap(result));
    }

    private String transition(
            Arguments args, DomainIdentifier actor, DomainIdentifier correlation, String permission, String operation) {
        DomainIdentifier id = args.requiredId("id");
        Partner current = partners.get(id);
        String reason = args.required("reason");
        require(actor, permission, AuthorizationScope.organization(current.governingOrganizationId()),
                correlation, "itam-partner", id.toString(), reason);
        if (args.flag("dry-run")) return render(args, Map.of("dryRun", true, "operation", operation, "partnerId", id.toString()));
        PartnerCommandContext context = new PartnerCommandContext(actor, correlation, args.required("idempotency-key"), reason);
        Partner result = switch (operation) {
            case "submit-approval" -> partners.submitApproval(id, args.version(), context);
            case "authorize" -> partners.authorize(id, args.version(), context);
            case "suspend" -> partners.suspend(id, args.version(), context);
            default -> throw new IllegalArgumentException("unsupported transition");
        };
        return render(args, partnerMap(result));
    }

    private PartnerCommandContext context(
            Arguments args, DomainIdentifier actor, DomainIdentifier correlation, String reason) {
        return new PartnerCommandContext(actor, correlation, args.required("idempotency-key"), reason);
    }

    private CreatePartnerCommand command(JsonNode root, DomainIdentifier organization) {
        Set<String> roles = new LinkedHashSet<>();
        for (JsonNode value : requiredArray(root, "roles")) roles.add(requireText(value, "roles[]"));
        List<String> aliases = new ArrayList<>();
        for (JsonNode value : optionalArray(root, "aliases")) aliases.add(requireText(value, "aliases[]"));
        List<PartnerExternalId> externalIds = new ArrayList<>();
        for (JsonNode value : optionalArray(root, "externalIds")) {
            externalIds.add(new PartnerExternalId(requiredText(value, "authority"), requiredText(value, "value")));
        }
        List<PartnerAccreditation> accreditations = new ArrayList<>();
        for (JsonNode value : optionalArray(root, "accreditations")) {
            accreditations.add(new PartnerAccreditation(requiredText(value, "code"), requiredText(value, "issuer"),
                    LocalDate.parse(requiredText(value, "validFrom")), optionalDate(value, "validUntil"), requiredText(value, "evidenceReference")));
        }
        List<PartnerContact> contacts = new ArrayList<>();
        for (JsonNode value : optionalArray(root, "contacts")) {
            contacts.add(new PartnerContact(requiredText(value, "type"), requiredText(value, "name"),
                    optionalText(value, "email"), optionalText(value, "phone"), optionalText(value, "uri")));
        }
        return new CreatePartnerCommand(
                organization, optionalId(root, "governingSubdivisionId"), optionalText(root, "code"), requiredText(root, "legalName"),
                requiredText(root, "displayName"), requiredText(root, "countryCode"), Set.copyOf(roles),
                LocalDate.parse(requiredText(root, "validFrom")), optionalDate(root, "validUntil"), optionalText(root, "officialWebsite"),
                optionalText(root, "supportPortal"), List.copyOf(aliases), List.copyOf(externalIds), List.copyOf(accreditations), List.copyOf(contacts));
    }

    private void require(DomainIdentifier actor, String permission, AuthorizationScope scope,
            DomainIdentifier correlation, String targetType, String targetId, String justificationValue) {
        AuthorizationDecision decision = authorization.decide(actor, permission, scope, correlation, targetType, targetId, "CLI");
        if (!decision.allowed()) throw new CliAuthorizationException(decision.explanation());
        if (!features.supportsAdvancedAuthorization()) return;
        boolean justification = justificationValue != null && validJustification(justificationValue);
        String capabilityVersion = capabilities.snapshot().catalogVersion() + ":" + capabilities.snapshot().profileVersion();
        var request = new PolicyEvaluationRequest(actor, permission, targetType, targetId, scope,
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

    private static Map<String, Object> partnerMap(Partner p) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", p.id().toString()); result.put("governingOrganizationId", p.governingOrganizationId().toString());
        result.put("governingSubdivisionId", p.governingSubdivisionId() == null ? null : p.governingSubdivisionId().toString());
        result.put("code", p.code().value()); result.put("legalName", p.legalName()); result.put("displayName", p.displayName());
        result.put("countryCode", p.countryCode()); result.put("roles", p.roles().stream().map(PartnerRole::wireValue).sorted().toList());
        result.put("authorizationStatus", p.authorizationStatus().wireValue()); result.put("validFrom", p.validFrom().toString());
        result.put("validUntil", p.validUntil() == null ? null : p.validUntil().toString()); result.put("version", p.version());
        return result;
    }

    private String render(Arguments args, Object value) {
        if (!args.json()) return value.toString();
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot render CLI JSON", failure); }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field); return requireText(value, field);
    }
    private static String requireText(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.textValue().strip();
    }
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || value.isNull()) return null; return requireText(value, field);
    }
    private static LocalDate optionalDate(JsonNode node, String field) {
        String value = optionalText(node, field); return value == null ? null : LocalDate.parse(value);
    }
    private static DomainIdentifier optionalId(JsonNode node, String field) {
        String value = optionalText(node, field); return value == null ? null : DomainIdentifier.parse(value);
    }
    private static Iterable<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || !value.isArray() || value.size() == 0) throw new IllegalArgumentException(field + " must be a non-empty array"); return value;
    }
    private static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || value.isNull()) return List.of(); if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array"); return value;
    }
    private static boolean validJustification(String value) {
        String normalized = value.strip(); return normalized.length() >= 8 && normalized.length() <= 500 && normalized.chars().noneMatch(Character::isISOControl);
    }
    private static char[] readSecret(String pathValue) {
        Path path = Path.of(pathValue); if (!path.isAbsolute()) throw new IllegalArgumentException("--password-file must be an absolute path");
        byte[] bytes;
        try { bytes = Files.readAllBytes(path); } catch (IOException failure) { throw new IllegalArgumentException("--password-file is unreadable", failure); }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            while (decoded.hasRemaining() && Character.isWhitespace(decoded.get(decoded.limit() - 1))) decoded.limit(decoded.limit() - 1);
            if (!decoded.hasRemaining()) throw new IllegalArgumentException("--password-file is empty");
            char[] secret = new char[decoded.remaining()]; decoded.get(secret); return secret;
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("--password-file must contain valid UTF-8", failure); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static String safe(String value) {
        if (value == null) return "request failed"; String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip(); return normalized.length() <= 400 ? normalized : normalized.substring(0, 400);
    }
    private static boolean has(String[] values, String target) { for (String value : values) if (target.equals(value)) return true; return false; }
    private static String help() {
        return """
                InfraNexum ITAM Partner CLI
                  itam partner list --username USER --password-file ABS [--organization-id UUID] [--role ROLE] [--status STATUS] [--output json]
                  itam partner create --username USER --password-file ABS --input-file ABS --idempotency-key KEY [--reason TEXT] [--dry-run] [--output json]
                  itam partner submit-approval|authorize|suspend --id UUID --version N --reason TEXT --idempotency-key KEY --username USER --password-file ABS [--dry-run] [--output json]
                Secrets are accepted only through --password-file. Create input is read from JSON to preserve structured contacts, accreditations, aliases and external IDs.
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
                String token = input[index]; if (!token.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + token);
                String key = token.substring(2);
                if (Set.of("dry-run", "json").contains(key)) { flags.add(key); continue; }
                if (index + 1 >= input.length || input[index + 1].startsWith("--")) throw new IllegalArgumentException(token + " requires a value");
                if (values.putIfAbsent(key, input[++index]) != null) throw new IllegalArgumentException("duplicate option: " + token);
            }
            if ("json".equalsIgnoreCase(values.get("output"))) flags.add("json");
            return new Arguments(input[0], input[1], input[2], Map.copyOf(values), Set.copyOf(flags));
        }
        boolean has(String key) { return values.containsKey(key) || flags.contains(key); }
        boolean flag(String key) { return flags.contains(key); }
        boolean json() { return flag("json"); }
        String required(String key) { String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required"); return value.strip(); }
        String optional(String key, String fallback) { String value = values.get(key); return value == null || value.isBlank() ? fallback : value.strip(); }
        DomainIdentifier requiredId(String key) { return DomainIdentifier.parse(required(key)); }
        int limit() { int value = Integer.parseInt(optional("limit", "50")); if (value < 1 || value > 200) throw new IllegalArgumentException("--limit must be between 1 and 200"); return value; }
        long version() { long value = Long.parseLong(required("version")); if (value < 1) throw new IllegalArgumentException("--version must be positive"); return value; }
    }
}
