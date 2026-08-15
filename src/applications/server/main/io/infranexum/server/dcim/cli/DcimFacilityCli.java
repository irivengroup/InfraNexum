package io.infranexum.server.dcim.cli;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.dcim.facility.application.CreateFacilityCommand;
import io.infranexum.dcim.facility.application.FacilityApplicationService;
import io.infranexum.dcim.facility.application.FacilityCommandContext;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.application.UpdateFacilityCommand;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityNotFoundException;
import io.infranexum.dcim.facility.domain.FacilityQuotaException;
import io.infranexum.dcim.facility.domain.FacilityStatus;
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
import io.infranexum.server.platform.PlatformCapabilityService;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-owned CLI for the PGM-07-E04 physical hierarchy using the same RBAC/ABAC and use cases as HTTP. */
public final class DcimFacilityCli {
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
    private final FacilityApplicationService facilities;
    private final UuidV7Generator ids;
    private final JsonMapper json = JsonMapper.builder().build();

    public DcimFacilityCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            FacilityApplicationService facilities,
            UuidV7Generator ids) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.policyDecisions = Objects.requireNonNull(policyDecisions, "policyDecisions");
        this.features = Objects.requireNonNull(features, "features");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.facilities = Objects.requireNonNull(facilities, "facilities");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

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
            String result = execute(args, authenticated.account().id(), ids.next());
            if (!result.isEmpty()) out.println(result);
            out.flush(); return EXIT_OK;
        } catch (CliAuthorizationException failure) {
            err.println("authorization denied: " + safe(failure.getMessage())); err.flush(); return EXIT_AUTHORIZATION;
        } catch (IllegalArgumentException failure) {
            err.println("usage error: " + safe(failure.getMessage())); err.flush(); return EXIT_USAGE;
        } catch (FacilityConflictException failure) {
            err.println(failure.code() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (FacilityNotFoundException | FacilityQuotaException failure) {
            err.println(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage())); err.flush(); return EXIT_BUSINESS;
        } catch (RuntimeException failure) {
            if (authenticated == null) { err.println("authentication failed"); err.flush(); return EXIT_AUTHENTICATION; }
            err.println("internal CLI failure: " + failure.getClass().getSimpleName()); err.flush(); return EXIT_INTERNAL;
        } finally {
            if (authenticated != null) {
                try { authentication.logout(new ValidatedSession(authenticated.account(), authenticated.session())); }
                catch (RuntimeException ignored) { /* Best-effort ephemeral session cleanup. */ }
            }
        }
    }

    private String execute(Arguments args, DomainIdentifier actor, DomainIdentifier correlation) {
        if (!"dcim".equals(args.namespace())) throw new IllegalArgumentException("command must start with 'dcim'");
        FacilityKind kind = FacilityKind.parse(args.resource());
        return switch (args.operation()) {
            case "list" -> list(args, kind, actor, correlation);
            case "get" -> get(args, kind, actor, correlation);
            case "create" -> create(args, kind, actor, correlation);
            case "update" -> update(args, kind, actor, correlation);
            case "status" -> status(args, kind, actor, correlation);
            default -> throw new IllegalArgumentException("unknown DCIM operation: " + args.operation());
        };
    }

    private String list(Arguments args, FacilityKind kind, DomainIdentifier actor, DomainIdentifier correlation) {
        DomainIdentifier organization = args.requiredId("organization-id");
        require(actor, readPermission(kind), AuthorizationScope.organization(organization), correlation,
                "dcim-" + kind.wireValue(), "collection", null);
        var page = facilities.search(new FacilitySearchCriteria(
                organization, args.optionalId("subdivision-id"), kind, args.optionalId("parent-id"),
                args.has("status") ? FacilityStatus.parse(args.required("status")) : null,
                args.has("country-code") ? args.required("country-code") : null, args.optionalId("cursor"), args.limit()));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items", page.items().stream().map(DcimFacilityCli::facilityMap).toList());
        result.put("nextCursor", page.nextCursor() == null ? null : page.nextCursor().toString());
        return render(args, result);
    }

    private String get(Arguments args, FacilityKind kind, DomainIdentifier actor, DomainIdentifier correlation) {
        FacilityNode current = facilities.get(args.requiredId("id")); requireKind(kind, current);
        require(actor, readPermission(kind), AuthorizationScope.organization(current.organizationId()), correlation,
                "dcim-" + kind.wireValue(), current.id().toString(), null);
        return render(args, facilityMap(current));
    }

    private String create(Arguments args, FacilityKind kind, DomainIdentifier actor, DomainIdentifier correlation) {
        JsonNode root = readJson(args.required("input-file"));
        DomainIdentifier organization = DomainIdentifier.parse(requiredText(root, "organizationId"));
        String reason = reason(root, args);
        require(actor, createPermission(kind), AuthorizationScope.organization(organization), correlation,
                "dcim-" + kind.wireValue(), "collection", reason);
        CreateFacilityCommand command = new CreateFacilityCommand(
                kind, organization, DomainIdentifier.parse(requiredText(root,"subdivisionId")), optionalId(root,"parentId"),
                requiredText(root,"code"), requiredText(root,"displayName"), optionalText(root,"addressLine1"), optionalText(root,"addressLine2"),
                optionalText(root,"postalCode"), optionalText(root,"city"), optionalText(root,"countryCode"), optionalText(root,"timezone"),
                decimal(root,"latitude"), decimal(root,"longitude"), integer(root,"floorCount"), integer(root,"levelNumber"),
                decimal(root,"areaM2"), decimal(root,"levelHeightM"), decimal(root,"capacityKw"), optionalText(root,"accessRestriction"),
                optionalText(root,"zoneType"), optionalText(root,"description"));
        if (args.flag("dry-run")) return render(args, Map.of("dryRun", true, "operation", "create", "kind", kind.wireValue(), "code", command.code()));
        return render(args, facilityMap(facilities.create(command, context(args, actor, correlation, reason))));
    }

    private String update(Arguments args, FacilityKind kind, DomainIdentifier actor, DomainIdentifier correlation) {
        FacilityNode current = facilities.get(args.requiredId("id")); requireKind(kind,current);
        JsonNode root = readJson(args.required("input-file")); String reason=reason(root,args);
        require(actor, updatePermission(kind), AuthorizationScope.organization(current.organizationId()), correlation,
                "dcim-" + kind.wireValue(), current.id().toString(), reason);
        UpdateFacilityCommand command = new UpdateFacilityCommand(requiredText(root,"displayName"), optionalText(root,"addressLine1"),
                optionalText(root,"addressLine2"), optionalText(root,"postalCode"), optionalText(root,"city"), optionalText(root,"countryCode"),
                optionalText(root,"timezone"), decimal(root,"latitude"), decimal(root,"longitude"), integer(root,"floorCount"),
                integer(root,"levelNumber"), decimal(root,"areaM2"), decimal(root,"levelHeightM"), decimal(root,"capacityKw"),
                optionalText(root,"accessRestriction"), optionalText(root,"zoneType"), optionalText(root,"description"));
        if(args.flag("dry-run")) return render(args,Map.of("dryRun",true,"operation","update","id",current.id().toString()));
        return render(args,facilityMap(facilities.update(current.id(),args.version(),command,context(args,actor,correlation,reason))));
    }

    private String status(Arguments args, FacilityKind kind, DomainIdentifier actor, DomainIdentifier correlation) {
        FacilityNode current=facilities.get(args.requiredId("id"));requireKind(kind,current);FacilityStatus target=FacilityStatus.parse(args.required("target"));String reason=args.required("reason");
        require(actor,statusPermission(kind,target),AuthorizationScope.organization(current.organizationId()),correlation,
                "dcim-"+kind.wireValue(),current.id().toString(),reason);
        if(args.flag("dry-run")) return render(args,Map.of("dryRun",true,"operation","status","target",target.wireValue(),"id",current.id().toString()));
        return render(args,facilityMap(facilities.changeStatus(current.id(),args.version(),target,context(args,actor,correlation,reason))));
    }

    private FacilityCommandContext context(Arguments args,DomainIdentifier actor,DomainIdentifier correlation,String reason){return new FacilityCommandContext(actor,correlation,args.required("idempotency-key"),reason);}

    private void require(DomainIdentifier actor,String permission,AuthorizationScope scope,DomainIdentifier correlation,String type,String target,String justificationValue){
        AuthorizationDecision decision=authorization.decide(actor,permission,scope,correlation,type,target,"CLI");if(!decision.allowed())throw new CliAuthorizationException(decision.explanation());if(!features.supportsAdvancedAuthorization())return;
        boolean justification=justificationValue!=null&&validJustification(justificationValue);String capabilityVersion=capabilities.snapshot().catalogVersion()+":"+capabilities.snapshot().profileVersion();
        var request=new PolicyEvaluationRequest(actor,permission,type,target,scope,Map.of("channel","CLI","justification_present",Boolean.toString(justification)),"LOCAL_SESSION",capabilityVersion,null,true);
        var advanced=policyDecisions.decide(request,correlation,"CLI");if(!advanced.permitted())throw new CliAuthorizationException(advanced.reasonCode());
        for(PolicyObligation obligation:advanced.obligations()){if(obligation==PolicyObligation.REQUIRE_JUSTIFICATION&&justification)continue;throw new CliAuthorizationException("required authorization obligation is not satisfied: "+obligation.name());}
    }

    private JsonNode readJson(String pathValue){Path path=Path.of(pathValue);if(!path.isAbsolute())throw new IllegalArgumentException("--input-file must be an absolute path");try{JsonNode root=json.readTree(Files.readString(path,StandardCharsets.UTF_8));if(root==null||!root.isObject())throw new IllegalArgumentException("--input-file root must be a JSON object");return root;}catch(IOException failure){throw new IllegalArgumentException("--input-file is unreadable or invalid JSON",failure);}}
    private String render(Arguments args,Object value){if(!args.json())return value.toString();try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("cannot render CLI JSON",failure);}}
    private static Map<String,Object> facilityMap(FacilityNode n){Map<String,Object> r=new LinkedHashMap<>();r.put("id",n.id().toString());r.put("kind",n.kind().wireValue());r.put("organizationId",n.organizationId().toString());r.put("subdivisionId",n.subdivisionId().toString());r.put("parentId",text(n.parentId()));r.put("code",n.code().value());r.put("displayName",n.displayName());r.put("status",n.status().wireValue());r.put("addressLine1",n.addressLine1());r.put("addressLine2",n.addressLine2());r.put("postalCode",n.postalCode());r.put("city",n.city());r.put("countryCode",n.countryCode());r.put("timezone",n.timezone());r.put("floorCount",n.floorCount());r.put("levelNumber",n.levelNumber());r.put("areaM2",n.areaM2());r.put("capacityKw",n.capacityKw());r.put("version",n.version());return r;}
    private static String text(DomainIdentifier v){return v==null?null:v.toString();}
    private static String requiredText(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isTextual()||v.textValue().isBlank())throw new IllegalArgumentException(f+" is required");return v.textValue().strip();}
    private static String optionalText(JsonNode n,String f){JsonNode v=n.get(f);return v==null||v.isNull()?null:requiredText(n,f);}
    private static DomainIdentifier optionalId(JsonNode n,String f){String v=optionalText(n,f);return v==null?null:DomainIdentifier.parse(v);}
    private static BigDecimal decimal(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||v.isNull())return null;if(!v.isNumber())throw new IllegalArgumentException(f+" must be numeric");return v.decimalValue();}
    private static Integer integer(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||v.isNull())return null;if(!v.isIntegralNumber())throw new IllegalArgumentException(f+" must be an integer");return v.intValue();}
    private static String reason(JsonNode root,Arguments args){JsonNode v=root.get("reason");return v==null?args.required("reason"):requiredText(root,"reason");}
    private static boolean validJustification(String value){String n=value.strip();return n.length()>=8&&n.length()<=500&&n.chars().noneMatch(Character::isISOControl);}
    private static void requireKind(FacilityKind kind,FacilityNode node){if(node.kind()!=kind)throw new IllegalArgumentException("facility kind does not match command resource");}
    private static String readPermission(FacilityKind k){return switch(k){case SITE->PermissionCodes.DCIM_SITE_READ;case BUILDING->PermissionCodes.DCIM_BUILDING_READ;case FLOOR->PermissionCodes.DCIM_FLOOR_READ;case ROOM->PermissionCodes.DCIM_ROOM_READ;case ZONE->PermissionCodes.DCIM_ZONE_READ;};}
    private static String createPermission(FacilityKind k){return switch(k){case SITE->PermissionCodes.DCIM_SITE_CREATE;case BUILDING->PermissionCodes.DCIM_BUILDING_CREATE;case FLOOR->PermissionCodes.DCIM_FLOOR_CREATE;case ROOM->PermissionCodes.DCIM_ROOM_CREATE;case ZONE->PermissionCodes.DCIM_ZONE_CREATE;};}
    private static String updatePermission(FacilityKind k){return switch(k){case SITE->PermissionCodes.DCIM_SITE_UPDATE;case BUILDING->PermissionCodes.DCIM_BUILDING_UPDATE;case FLOOR->PermissionCodes.DCIM_FLOOR_UPDATE;case ROOM->PermissionCodes.DCIM_ROOM_UPDATE;case ZONE->PermissionCodes.DCIM_ZONE_UPDATE;};}
    private static String statusPermission(FacilityKind k,FacilityStatus target){if(target==FacilityStatus.DELETED)return switch(k){case SITE->PermissionCodes.DCIM_SITE_DELETE;case BUILDING->PermissionCodes.DCIM_BUILDING_DELETE;case FLOOR->PermissionCodes.DCIM_FLOOR_DELETE;case ROOM->PermissionCodes.DCIM_ROOM_DELETE;case ZONE->PermissionCodes.DCIM_ZONE_DELETE;};if(target==FacilityStatus.ARCHIVED)return switch(k){case SITE->PermissionCodes.DCIM_SITE_ARCHIVE;case BUILDING->PermissionCodes.DCIM_BUILDING_ARCHIVE;case FLOOR->PermissionCodes.DCIM_FLOOR_ARCHIVE;case ROOM->PermissionCodes.DCIM_ROOM_ARCHIVE;case ZONE->PermissionCodes.DCIM_ZONE_UPDATE;};if(k==FacilityKind.ROOM&&target==FacilityStatus.LOCKED)return PermissionCodes.DCIM_ROOM_LOCK;return updatePermission(k);}
    private static char[] readSecret(String pathValue){Path path=Path.of(pathValue);if(!path.isAbsolute())throw new IllegalArgumentException("--password-file must be an absolute path");byte[] bytes;try{bytes=Files.readAllBytes(path);}catch(IOException failure){throw new IllegalArgumentException("--password-file is unreadable",failure);}try{CharBuffer decoded=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));while(decoded.hasRemaining()&&Character.isWhitespace(decoded.get(decoded.limit()-1)))decoded.limit(decoded.limit()-1);if(!decoded.hasRemaining())throw new IllegalArgumentException("--password-file is empty");char[] secret=new char[decoded.remaining()];decoded.get(secret);return secret;}catch(CharacterCodingException failure){throw new IllegalArgumentException("--password-file must contain valid UTF-8",failure);}finally{Arrays.fill(bytes,(byte)0);}}
    private static String safe(String v){if(v==null)return "request failed";String n=v.replaceAll("[\\r\\n\\t]+"," ").strip();return n.length()<=400?n:n.substring(0,400);}
    private static boolean has(String[] values,String target){for(String v:values)if(target.equals(v))return true;return false;}
    private static String help(){return """
            InfraNexum DCIM Facility CLI
              dcim site|building|floor|room|zone list --organization-id UUID --username USER --password-file ABS [--subdivision-id UUID] [--parent-id UUID] [--status STATUS] [--output json]
              dcim site|building|floor|room|zone get --id UUID --username USER --password-file ABS [--output json]
              dcim site|building|floor|room|zone create --input-file ABS --idempotency-key KEY --username USER --password-file ABS [--reason TEXT] [--dry-run] [--output json]
              dcim site|building|floor|room|zone update --id UUID --version N --input-file ABS --idempotency-key KEY --username USER --password-file ABS [--reason TEXT] [--dry-run] [--output json]
              dcim site|building|floor|room|zone status --id UUID --version N --target STATUS --reason TEXT --idempotency-key KEY --username USER --password-file ABS [--dry-run] [--output json]
            Secrets are accepted only through --password-file. Structured create/update payloads are read from JSON files.
            """;}

    private static final class CliAuthorizationException extends RuntimeException{private static final long serialVersionUID=1L;CliAuthorizationException(String message){super(message);}}
    private record Arguments(String namespace,String resource,String operation,Map<String,String> values,Set<String> flags){
        static Arguments parse(String[] input){if(input.length<3)throw new IllegalArgumentException("namespace, resource and operation are required");Map<String,String> values=new LinkedHashMap<>();Set<String> flags=new LinkedHashSet<>();for(int i=3;i<input.length;i++){String token=input[i];if(!token.startsWith("--"))throw new IllegalArgumentException("unexpected argument: "+token);String key=token.substring(2);if(Set.of("dry-run","json").contains(key)){flags.add(key);continue;}if(i+1>=input.length||input[i+1].startsWith("--"))throw new IllegalArgumentException(token+" requires a value");if(values.putIfAbsent(key,input[++i])!=null)throw new IllegalArgumentException("duplicate option: "+token);}if("json".equalsIgnoreCase(values.get("output")))flags.add("json");return new Arguments(input[0],input[1],input[2],Map.copyOf(values),Set.copyOf(flags));}
        boolean has(String k){return values.containsKey(k)||flags.contains(k);}boolean flag(String k){return flags.contains(k);}boolean json(){return flag("json");}
        String required(String k){String v=values.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("--"+k+" is required");return v.strip();}
        DomainIdentifier requiredId(String k){return DomainIdentifier.parse(required(k));}DomainIdentifier optionalId(String k){String v=values.get(k);return v==null||v.isBlank()?null:DomainIdentifier.parse(v);}
        int limit(){int v=Integer.parseInt(values.getOrDefault("limit","50"));if(v<1||v>200)throw new IllegalArgumentException("--limit must be between 1 and 200");return v;}
        long version(){long v=Long.parseLong(required("version"));if(v<1)throw new IllegalArgumentException("--version must be positive");return v;}
    }
}
