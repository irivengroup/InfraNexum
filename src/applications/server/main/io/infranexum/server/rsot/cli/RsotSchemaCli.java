package io.infranexum.server.rsot.cli;

import io.infranexum.core.compatibility.CompatibilityReport;
import io.infranexum.core.compatibility.CreateProfileCommand;
import io.infranexum.core.compatibility.CreateSchemaCommand;
import io.infranexum.core.compatibility.RegisteredSchema;
import io.infranexum.core.compatibility.RegistryStatus;
import io.infranexum.core.compatibility.SchemaKind;
import io.infranexum.core.compatibility.SchemaProfile;
import io.infranexum.core.compatibility.SchemaRegistryCommandContext;
import io.infranexum.core.compatibility.SchemaRegistryException;
import io.infranexum.core.compatibility.SchemaRegistryService;
import io.infranexum.core.capabilities.CapabilityUnavailableException;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.AuthorizationScope;
import io.infranexum.identity.access.domain.PermissionCodes;
import io.infranexum.identity.local.application.AuthenticatedSession;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.application.ValidatedSession;
import io.infranexum.server.configuration.ServerTemporalInputParser;
import io.infranexum.server.rsot.JacksonSchemaDefinitionInspector;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Server-owned CLI exposing the same PGM-06-E03 use cases and RBAC decisions as HTTP. */
public final class RsotSchemaCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_AUTHENTICATION = 3;
    public static final int EXIT_AUTHORIZATION = 4;
    public static final int EXIT_BUSINESS = 5;
    public static final int EXIT_INTERNAL = 70;

    private final LocalAuthenticationService authentication;
    private final RbacAuthorizationService authorization;
    private final SchemaRegistryService registry;
    private final JacksonSchemaDefinitionInspector inspector;
    private final ServerTemporalInputParser temporal;
    private final UuidV7Generator ids;

    public RsotSchemaCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            SchemaRegistryService registry,
            JacksonSchemaDefinitionInspector inspector,
            ServerTemporalInputParser temporal,
            UuidV7Generator ids) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.temporal = Objects.requireNonNull(temporal, "temporal");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** Executes one CLI command and returns a stable process exit code. */
    public int run(String[] arguments, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (arguments.length == 0 || has(arguments, "--help") || has(arguments, "-h")) {
            out.print(help());
            out.flush();
            return EXIT_OK;
        }
        AuthenticatedSession authenticated = null;
        try {
            Arguments args = Arguments.parse(arguments);
            char[] password = readSecret(args.required("password-file"));
            try {
                authenticated = authentication.authenticate(args.required("username"), password);
            } catch (RuntimeException failure) {
                throw new CliAuthenticationException(failure);
            } finally {
                Arrays.fill(password, '\0');
            }
            DomainIdentifier actor = authenticated.account().id();
            DomainIdentifier correlation = ids.next();
            SchemaRegistryCommandContext context = new SchemaRegistryCommandContext(actor, correlation);
            String rendered = execute(args, actor, correlation, context);
            if (!rendered.isEmpty()) out.println(rendered);
            out.flush();
            return EXIT_OK;
        } catch (CliAuthenticationException failure) {
            err.println("authentication failed");
            err.flush();
            return EXIT_AUTHENTICATION;
        } catch (CliAuthorizationException failure) {
            err.println("authorization denied: " + safe(failure.getMessage()));
            err.flush();
            return EXIT_AUTHORIZATION;
        } catch (CapabilityUnavailableException failure) {
            err.println("capability unavailable: rsot.core");
            err.flush();
            return EXIT_AUTHORIZATION;
        } catch (IllegalArgumentException failure) {
            err.println("usage error: " + safe(failure.getMessage()));
            err.flush();
            return EXIT_USAGE;
        } catch (SchemaRegistryException failure) {
            err.println(failure.code() + ": " + safe(failure.getMessage()));
            err.flush();
            return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            // Authentication services deliberately expose no secret-bearing diagnostic at the process boundary.
            if (authenticated == null) {
                err.println("authentication failed");
                err.flush();
                return EXIT_AUTHENTICATION;
            }
            err.println("internal CLI failure: " + failure.getClass().getSimpleName());
            err.flush();
            return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try {
                    authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session()));
                } catch (RuntimeException ignored) {
                    // Best-effort session cleanup must not replace the command's primary result.
                }
            }
        }
    }

    private String execute(
            Arguments args, DomainIdentifier actor, DomainIdentifier correlation, SchemaRegistryCommandContext context) {
        if (!"rsot".equals(args.namespace())) throw new IllegalArgumentException("first argument must be 'rsot'");
        return switch (args.resource()) {
            case "schema" -> schema(args, actor, correlation, context);
            case "schema-profile", "profile" -> profile(args, actor, correlation, context);
            default -> throw new IllegalArgumentException("unknown RSOT resource: " + args.resource());
        };
    }

    private String schema(
            Arguments args, DomainIdentifier actor, DomainIdentifier correlation, SchemaRegistryCommandContext context) {
        return switch (args.operation()) {
            case "create" -> {
                require(actor, PermissionCodes.RSOT_SCHEMA_CREATE, correlation, "rsot_schema", "collection");
                String definition = readDefinition(args.required("definition-file"));
                SchemaKind kind = enumValue(SchemaKind.class, args.required("kind"), "--kind");
                inspector.validate(kind, definition);
                if (args.flag("dry-run")) yield dryRun(args, "create schema " + args.required("key") + "@" + args.required("version"));
                RegisteredSchema created = registry.createSchema(new CreateSchemaCommand(
                        args.required("key"), kind, args.required("owner"), args.required("version"), definition,
                        temporal.optionalInstant(args.optional("effective-at", null), "effectiveAt")), context);
                yield render(args, schemaMap(created));
            }
            case "list" -> {
                require(actor, PermissionCodes.RSOT_SCHEMA_READ, correlation, "rsot_schema", "collection");
                SchemaKind kind = args.has("kind") ? enumValue(SchemaKind.class, args.required("kind"), "--kind") : null;
                RegistryStatus status = args.has("status") ? enumValue(RegistryStatus.class, args.required("status"), "--status") : null;
                yield renderList(args, registry.listSchemas(args.optional("key", null), kind, status, args.offset(), args.limit()).stream()
                        .map(RsotSchemaCli::schemaMap).toList());
            }
            case "show" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_READ, correlation, "rsot_schema", id.toString());
                yield render(args, schemaMap(registry.getSchema(id)));
            }
            case "update" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_UPDATE, correlation, "rsot_schema", id.toString());
                String definition = readDefinition(args.required("definition-file"));
                if (args.flag("dry-run")) yield dryRun(args, "update draft schema " + id);
                yield render(args, schemaMap(registry.updateDraft(id, args.revision(), definition, context)));
            }
            case "compatibility" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_READ, correlation, "rsot_schema", id.toString());
                CompatibilityReport report = registry.previewCompatibility(id);
                yield render(args, ordered("verdict", report.verdict().name(), "issues", report.issues()));
            }
            case "publish" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_PUBLISH, correlation, "rsot_schema", id.toString());
                if (args.flag("dry-run")) yield dryRun(args, "publish schema " + id);
                yield render(args, schemaMap(registry.publish(id, args.revision(), args.optional("breaking-approval", null), context)));
            }
            case "deprecate" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_DEPRECATE, correlation, "rsot_schema", id.toString());
                Instant sunset = requiredInstant(args.required("sunset-at"), "sunsetAt");
                if (args.flag("dry-run")) yield dryRun(args, "deprecate schema " + id);
                yield render(args, schemaMap(registry.deprecate(id, args.revision(), sunset, args.required("reason"), context)));
            }
            default -> throw new IllegalArgumentException("unknown schema operation: " + args.operation());
        };
    }

    private String profile(
            Arguments args, DomainIdentifier actor, DomainIdentifier correlation, SchemaRegistryCommandContext context) {
        return switch (args.operation()) {
            case "create" -> {
                require(actor, PermissionCodes.RSOT_SCHEMA_CREATE, correlation, "rsot_schema_profile", "collection");
                List<DomainIdentifier> schemaIds = args.requiredCsv("schema-ids").stream().map(DomainIdentifier::parse).toList();
                String requestedCode = args.optional("code", null);
                if (args.flag("dry-run")) yield dryRun(args, "create schema profile " + (requestedCode == null ? "<auto>" : requestedCode) + "@" + args.required("version"));
                yield render(args, profileMap(registry.createProfile(new CreateProfileCommand(
                        requestedCode, args.required("owner"), args.required("version"), schemaIds), context)));
            }
            case "list" -> {
                require(actor, PermissionCodes.RSOT_SCHEMA_READ, correlation, "rsot_schema_profile", "collection");
                RegistryStatus status = args.has("status") ? enumValue(RegistryStatus.class, args.required("status"), "--status") : null;
                yield renderList(args, registry.listProfiles(args.optional("code", null), status, args.offset(), args.limit()).stream()
                        .map(RsotSchemaCli::profileMap).toList());
            }
            case "show" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_READ, correlation, "rsot_schema_profile", id.toString());
                yield render(args, profileMap(registry.getProfile(id)));
            }
            case "publish" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_PUBLISH, correlation, "rsot_schema_profile", id.toString());
                if (args.flag("dry-run")) yield dryRun(args, "publish schema profile " + id);
                yield render(args, profileMap(registry.publishProfile(id, args.revision(), context)));
            }
            case "deprecate" -> {
                DomainIdentifier id = args.requiredId("id");
                require(actor, PermissionCodes.RSOT_SCHEMA_DEPRECATE, correlation, "rsot_schema_profile", id.toString());
                Instant sunset = requiredInstant(args.required("sunset-at"), "sunsetAt");
                if (args.flag("dry-run")) yield dryRun(args, "deprecate schema profile " + id);
                yield render(args, profileMap(registry.deprecateProfile(id, args.revision(), sunset, args.required("reason"), context)));
            }
            default -> throw new IllegalArgumentException("unknown schema-profile operation: " + args.operation());
        };
    }

    private void require(
            DomainIdentifier actor, String permission, DomainIdentifier correlation, String targetType, String targetId) {
        var decision = authorization.decide(actor, permission, AuthorizationScope.platform(), correlation, targetType, targetId, "CLI");
        if (!decision.allowed()) throw new CliAuthorizationException(decision.explanation());
    }

    private Instant requiredInstant(String value, String field) {
        Instant result = temporal.optionalInstant(value, field);
        if (result == null) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String readDefinition(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--definition-file must be an absolute path");
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) throw new IllegalArgumentException("--definition-file is empty");
            return json;
        } catch (IOException failure) {
            throw new IllegalArgumentException("--definition-file is unreadable", failure);
        }
    }

    private static char[] readSecret(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--password-file must be an absolute path");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("--password-file is unreadable", failure);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            while (decoded.hasRemaining() && Character.isWhitespace(decoded.get(decoded.limit() - 1))) decoded.limit(decoded.limit() - 1);
            if (!decoded.hasRemaining()) throw new IllegalArgumentException("--password-file is empty");
            char[] secret = new char[decoded.remaining()];
            decoded.get(secret);
            return secret;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("--password-file must contain valid UTF-8", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String option) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(option + " has an unsupported value", failure);
        }
    }

    private static Map<String, ?> schemaMap(RegisteredSchema schema) {
        return ordered("id", schema.id().toString(), "schemaKey", schema.schemaKey(), "kind", schema.kind().name(),
                "owner", schema.owner(), "version", schema.version().toString(), "status", schema.status().name(),
                "revision", schema.revision(), "checksumSha256", schema.checksumSha256(), "effectiveAt", schema.effectiveAt().toString(),
                "publishedAt", nullable(schema.publishedAt()), "sunsetAt", nullable(schema.sunsetAt()));
    }

    private static Map<String, ?> profileMap(SchemaProfile profile) {
        return ordered("id", profile.id().toString(), "code", profile.code(), "owner", profile.owner(),
                "version", profile.version().toString(), "status", profile.status().name(), "revision", profile.revision(),
                "checksumSha256", profile.checksumSha256(), "schemaIds", profile.members().stream().map(member -> member.schemaId().toString()).toList(),
                "publishedAt", nullable(profile.publishedAt()), "sunsetAt", nullable(profile.sunsetAt()));
    }

    private static Object nullable(Object value) { return value == null ? null : value.toString(); }

    private static String render(Arguments args, Map<String, ?> value) { return args.json() ? Json.write(value) : text(value); }
    private static String renderList(Arguments args, List<Map<String, ?>> values) {
        if (args.json()) return Json.write(values);
        if (values.isEmpty()) return "no results";
        return values.stream().map(RsotSchemaCli::text).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
    private static String dryRun(Arguments args, String operation) { return render(args, ordered("dryRun", true, "operation", operation)); }
    private static String text(Map<String, ?> value) {
        return value.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(" "));
    }
    private static Map<String, ?> ordered(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
    private static boolean has(String[] args, String value) { return Arrays.asList(args).contains(value); }
    private static String safe(String value) { return value == null || value.isBlank() ? "operation failed" : value; }

    public static String help() {
        return """
                InfraNexum RSOT Schema Registry CLI — PGM-06-E03

                Usage:
                  infranexum rsot schema <create|list|show|update|compatibility|publish|deprecate> [options]
                  infranexum rsot schema-profile <create|list|show|publish|deprecate> [options]
                  --username <local-login> --password-file </absolute/secret/path> [--format text|json] [--dry-run]

                Mutation inputs:
                  create schema: --key --kind --owner --version --definition-file [--effective-at]
                  update schema: --id --revision --definition-file
                  publish schema: --id --revision [--breaking-approval]
                  deprecate:     --id --revision --sunset-at --reason
                  create profile: --code --owner --version --schema-ids <uuid,uuid,...>

                Safety:
                  passwords and schema documents are read from files, never embedded in argv; mutations support --dry-run.
                """;
    }

    private static final class CliAuthenticationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CliAuthenticationException(Throwable cause) { super(cause); }
    }
    private static final class CliAuthorizationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CliAuthorizationException(String message) { super(message); }
    }

    /** Strict parser rejecting duplicate options and unexpected positional arguments. */
    static final class Arguments {
        private static final Set<String> FLAGS = Set.of("dry-run");
        private final String namespace;
        private final String resource;
        private final String operation;
        private final Map<String, String> options;
        private Arguments(String namespace, String resource, String operation, Map<String, String> options) {
            this.namespace = namespace; this.resource = resource; this.operation = operation; this.options = Map.copyOf(options);
        }
        static Arguments parse(String[] raw) {
            if (raw.length < 3) throw new IllegalArgumentException("expected: rsot <resource> <operation>");
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = 3; index < raw.length; index++) {
                String token = raw[index];
                if (!token.startsWith("--") || token.length() < 3) throw new IllegalArgumentException("unexpected positional argument: " + token);
                String name = token.substring(2);
                if (options.containsKey(name)) throw new IllegalArgumentException("duplicate option --" + name);
                if (FLAGS.contains(name)) options.put(name, "true");
                else {
                    if (++index >= raw.length || raw[index].startsWith("--")) throw new IllegalArgumentException("option --" + name + " requires a value");
                    options.put(name, raw[index]);
                }
            }
            return new Arguments(raw[0].toLowerCase(Locale.ROOT), raw[1].toLowerCase(Locale.ROOT), raw[2].toLowerCase(Locale.ROOT), options);
        }
        String namespace() { return namespace; }
        String resource() { return resource; }
        String operation() { return operation; }
        boolean has(String name) { return options.containsKey(name); }
        boolean flag(String name) { return "true".equals(options.get(name)); }
        String required(String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + name);
            return value;
        }
        String optional(String name, String fallback) {
            String value = options.get(name);
            return value == null || value.isBlank() ? fallback : value;
        }
        DomainIdentifier requiredId(String name) { return DomainIdentifier.parse(required(name)); }
        List<String> requiredCsv(String name) {
            List<String> values = Arrays.stream(required(name).split(",", -1)).map(String::strip).filter(value -> !value.isEmpty()).toList();
            if (values.isEmpty()) throw new IllegalArgumentException("--" + name + " must contain at least one value");
            return values;
        }
        long revision() {
            try {
                long value = Long.parseLong(required("revision"));
                if (value < 1) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) { throw new IllegalArgumentException("--revision must be a positive integer", failure); }
        }
        int offset() { return integer("offset", 0, 0, Integer.MAX_VALUE); }
        int limit() { return integer("limit", 50, 1, 200); }
        boolean json() {
            String format = optional("format", "text").toLowerCase(Locale.ROOT);
            if (!Set.of("text", "json").contains(format)) throw new IllegalArgumentException("--format must be text or json");
            return "json".equals(format);
        }
        private int integer(String name, int fallback, int minimum, int maximum) {
            if (!has(name)) return fallback;
            try {
                int value = Integer.parseInt(required(name));
                if (value < minimum || value > maximum) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) { throw new IllegalArgumentException("--" + name + " is outside the supported range", failure); }
        }
    }

    /** Deterministic encoder for structured CLI output. */
    static final class Json {
        private Json() {}
        static String write(Object value) {
            if (value == null) return "null";
            if (value instanceof Boolean || value instanceof Number) return value.toString();
            if (value instanceof CharSequence) return '"' + escape(value.toString()) + '"';
            if (value instanceof Map<?, ?> map) {
                StringBuilder out = new StringBuilder("{"); boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) { if (!first) out.append(','); first = false; out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":").append(write(entry.getValue())); }
                return out.append('}').toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder out = new StringBuilder("["); boolean first = true;
                for (Object element : iterable) { if (!first) out.append(','); first = false; out.append(write(element)); }
                return out.append(']').toString();
            }
            return write(value.toString());
        }
        private static String escape(String value) {
            StringBuilder out = new StringBuilder(value.length() + 16);
            for (int index = 0; index < value.length(); index++) {
                char c = value.charAt(index);
                switch (c) {
                    case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                    default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.toString();
        }
    }
}
