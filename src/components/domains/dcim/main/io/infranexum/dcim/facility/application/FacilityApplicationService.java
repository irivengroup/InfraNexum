package io.infranexum.dcim.facility.application;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.dcim.facility.domain.FacilityCode;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityNotFoundException;
import io.infranexum.dcim.facility.domain.FacilityQuotaException;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import io.infranexum.dcim.facility.ports.FacilityFeaturePolicy;
import io.infranexum.dcim.facility.ports.FacilityIdempotencyRepository;
import io.infranexum.dcim.facility.ports.FacilityRepository;
import io.infranexum.dcim.facility.ports.FacilityScopePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Application boundary for PGM-07-E04 sites, buildings, floors, rooms and technical zones. */
public final class FacilityApplicationService {
    private static final ContractVersion EVENT_VERSION = ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE = new EventSource("infranexum.dcim.facility");

    private final FacilityRepository facilities;
    private final FacilityIdempotencyRepository idempotency;
    private final FacilityFeaturePolicy features;
    private final FacilityScopePolicy scopes;
    private final TransactionalEventStore events;
    private final UuidV7Generator ids;
    private final Clock clock;

    public FacilityApplicationService(FacilityRepository facilities, FacilityIdempotencyRepository idempotency,
            FacilityFeaturePolicy features, FacilityScopePolicy scopes, TransactionalEventStore events,
            UuidV7Generator ids, Clock clock) {
        this.facilities=Objects.requireNonNull(facilities,"facilities"); this.idempotency=Objects.requireNonNull(idempotency,"idempotency");
        this.features=Objects.requireNonNull(features,"features"); this.scopes=Objects.requireNonNull(scopes,"scopes");
        this.events=Objects.requireNonNull(events,"events"); this.ids=Objects.requireNonNull(ids,"ids"); this.clock=Objects.requireNonNull(clock,"clock");
    }

    public FacilityNode create(CreateFacilityCommand command, FacilityCommandContext context) {
        Objects.requireNonNull(command,"command"); Objects.requireNonNull(context,"context"); requireEnabled();
        Objects.requireNonNull(command.kind(),"kind"); Objects.requireNonNull(command.organizationId(),"organizationId"); Objects.requireNonNull(command.subdivisionId(),"subdivisionId");
        scopes.requireActiveScope(command.organizationId(), command.subdivisionId());
        Parent parent = resolveParent(command);
        FacilityCode code = new FacilityCode(command.code());
        FacilityNode prototype = FacilityNode.draft(ids.next(), command.kind(), command.organizationId(), command.subdivisionId(),
                command.parentId(), parent.scopeId(), code, command.displayName(), command.addressLine1(), command.addressLine2(),
                command.postalCode(), command.city(), command.countryCode(), command.timezone(), command.latitude(), command.longitude(), command.floorCount(), command.levelNumber(), command.areaM2(),
                command.levelHeightM(), command.capacityKw(), command.accessRestriction(), command.zoneType(), command.description(),
                context.actorId(), context.reason(), clock.instant());
        String fingerprint=fingerprint("create", command.kind(), command.organizationId(), command.subdivisionId(), command.parentId(), code,
                prototype.displayName(), prototype.addressLine1(), prototype.addressLine2(), prototype.postalCode(), prototype.city(),
                prototype.countryCode(), prototype.timezone(), prototype.latitude(), prototype.longitude(),
                prototype.floorCount(), prototype.levelNumber(), prototype.areaM2(), prototype.levelHeightM(), prototype.capacityKw(),
                prototype.accessRestriction(), prototype.zoneType(), prototype.description());
        return execute(tx -> {
            Optional<FacilityIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());
            if(prior.isPresent()) return replay(prior.orElseThrow(),fingerprint,"create");
            if(facilities.count(command.kind())>=features.limit(command.kind())) throw new FacilityQuotaException(command.kind());
            if(facilities.existsByScopeCode(command.kind(), parent.scopeId(), code)) throw new FacilityConflictException("DCIM_CODE_DUPLICATE","facility code already exists in parent scope");
            facilities.insert(prototype); tx.append(event("dcim."+command.kind().wireValue()+".created.v1",prototype,context));
            idempotency.insert(new FacilityIdempotencyRepository.Record(context.idempotencyKey(),fingerprint,"create",prototype.id(),prototype.createdAt()));
            return prototype;
        });
    }

    public FacilityNode update(DomainIdentifier id, long expectedVersion, UpdateFacilityCommand command, FacilityCommandContext context) {
        Objects.requireNonNull(command,"command");
        return mutate(id,expectedVersion,context,"update", "updated", current -> current.updateMetadata(command.displayName(),
                current.kind()==FacilityKind.SITE?command.addressLine1():current.addressLine1(), current.kind()==FacilityKind.SITE?command.addressLine2():current.addressLine2(),
                current.kind()==FacilityKind.SITE?command.postalCode():current.postalCode(), current.kind()==FacilityKind.SITE?command.city():current.city(),
                current.kind()==FacilityKind.SITE?command.countryCode():current.countryCode(), current.kind()==FacilityKind.SITE?command.timezone():current.timezone(),
                command.latitude(),command.longitude(),command.floorCount(),command.levelNumber(),command.areaM2(),command.levelHeightM(),command.capacityKw(),
                command.accessRestriction(),command.zoneType(),command.description(),context.actorId(),context.reason(),clock.instant()));
    }

    public FacilityNode changeStatus(DomainIdentifier id, long expectedVersion, FacilityStatus target, FacilityCommandContext context) {
        Objects.requireNonNull(target,"target");
        return mutate(id,expectedVersion,context,"status:"+target.wireValue(),"status_changed",current -> {
            if (current.kind()==FacilityKind.SITE && (target==FacilityStatus.ARCHIVED || target==FacilityStatus.DELETED)
                    && facilities.activeBuildingsForSite(current.id())>0) {
                throw new FacilityConflictException("DCIM_SITE_ARCHIVE_BLOCKED","active buildings prevent site archival/deletion");
            }
            if (target==FacilityStatus.ACTIVE && current.parentId()!=null) {
                FacilityNode parent=requireFacility(current.parentId());
                if (parent.status()!=FacilityStatus.ACTIVE) throw new FacilityConflictException("DCIM_PARENT_INACTIVE","parent facility must be active");
            }
            return current.changeStatus(target,context.actorId(),context.reason(),clock.instant());
        });
    }

    public FacilityNode get(DomainIdentifier id) { requireEnabled(); return requireFacility(id); }
    public FacilityPage search(FacilitySearchCriteria criteria) { requireEnabled(); Objects.requireNonNull(criteria,"criteria"); return facilities.search(criteria); }

    private FacilityNode mutate(DomainIdentifier id,long expectedVersion,FacilityCommandContext context,String operation,String eventSuffix,Transition transition) {
        requireEnabled(); Objects.requireNonNull(id,"id"); Objects.requireNonNull(context,"context"); if(expectedVersion<1) throw new IllegalArgumentException("expectedVersion must be positive");
        String fingerprint=fingerprint(operation,id,expectedVersion,context.reason());
        return execute(tx -> {
            Optional<FacilityIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());
            if(prior.isPresent()) return replay(prior.orElseThrow(),fingerprint,operation);
            FacilityNode current=requireFacility(id); if(current.version()!=expectedVersion) throw new FacilityConflictException("VERSION_CONFLICT","facility version changed");
            FacilityNode changed=transition.apply(current); facilities.update(changed,expectedVersion);
            tx.append(event("dcim."+changed.kind().wireValue()+"."+eventSuffix+".v1",changed,context));
            appendSpecificLifecycleEvent(tx, changed, operation, context);
            idempotency.insert(new FacilityIdempotencyRepository.Record(context.idempotencyKey(),fingerprint,operation,changed.id(),clock.instant()));
            return changed;
        });
    }


    private void appendSpecificLifecycleEvent(io.infranexum.core.events.EventTransaction tx, FacilityNode changed,
            String operation, FacilityCommandContext context) {
        if (!operation.startsWith("status:")) return;
        if (changed.kind() == FacilityKind.SITE && changed.status() == FacilityStatus.ARCHIVED) {
            tx.append(event("dcim.site.archived.v1", changed, context));
        } else if (changed.kind() == FacilityKind.SITE && changed.status() == FacilityStatus.DELETED) {
            tx.append(event("dcim.site.deleted.v1", changed, context));
        } else if (changed.kind() == FacilityKind.ROOM && changed.status() == FacilityStatus.LOCKED) {
            tx.append(event("dcim.room.locked.v1", changed, context));
        }
    }

    private Parent resolveParent(CreateFacilityCommand command) {
        if(command.kind()==FacilityKind.SITE) return new Parent(command.subdivisionId());
        FacilityNode parent=requireFacility(Objects.requireNonNull(command.parentId(),"parentId"));
        FacilityKind expected=switch(command.kind()){case BUILDING->FacilityKind.SITE; case FLOOR->FacilityKind.BUILDING; case ROOM->FacilityKind.FLOOR; case ZONE->null; case SITE->throw new IllegalStateException();};
        if(command.kind()==FacilityKind.ZONE){
            if(!(parent.kind()==FacilityKind.SITE||parent.kind()==FacilityKind.BUILDING||parent.kind()==FacilityKind.FLOOR||parent.kind()==FacilityKind.ROOM)) throw new FacilityConflictException("DCIM_ZONE_PARENT_INVALID","technical zone parent must be site, building, floor or room");
        } else if(parent.kind()!=expected) throw new FacilityConflictException("DCIM_PARENT_KIND_INVALID","facility parent kind is invalid");
        if(!parent.organizationId().equals(command.organizationId())||!parent.subdivisionId().equals(command.subdivisionId())) throw new FacilityConflictException("DCIM_SCOPE_MISMATCH","facility parent belongs to another governance scope");
        if(parent.status()!=FacilityStatus.ACTIVE) throw new FacilityConflictException("DCIM_PARENT_INACTIVE","parent facility must be active");
        DomainIdentifier scopeId=command.kind()==FacilityKind.ZONE?siteAncestor(parent).id():parent.id();
        return new Parent(scopeId);
    }

    private FacilityNode siteAncestor(FacilityNode node){ FacilityNode current=node; while(current.kind()!=FacilityKind.SITE){ current=requireFacility(current.parentId()); } return current; }
    private FacilityNode replay(FacilityIdempotencyRepository.Record prior,String fingerprint,String operation){ if(!prior.operation().equals(operation)||!prior.payloadSha256().equals(fingerprint)) throw new FacilityConflictException("IDEMPOTENCY_CONFLICT","idempotency key was used with another payload"); return requireFacility(prior.facilityId()); }
    private FacilityNode requireFacility(DomainIdentifier id){ return facilities.findById(Objects.requireNonNull(id,"id")).orElseThrow(FacilityNotFoundException::new); }
    private void requireEnabled(){ if(!features.facilitiesEnabled()) throw new FacilityConflictException("DCIM_FACILITY_CAPABILITY_UNAVAILABLE","DCIM facilities capability is unavailable"); }

    private EventEnvelope event(String type,FacilityNode node,FacilityCommandContext context){
        String payload="{\"facility_id\":\""+node.id()+"\",\"kind\":\""+node.kind().wireValue()+"\",\"organization_id\":\""+node.organizationId()+"\",\"subdivision_id\":\""+node.subdivisionId()+"\",\"status\":\""+node.status().wireValue()+"\",\"version\":"+node.version()+"}";
        return new EventEnvelope(ids.next(), new EventType(type), EVENT_VERSION, clock.instant(), SOURCE,
                context.correlationId(), node.id(), payload);
    }
    private static String fingerprint(Object... values){ try{MessageDigest d=MessageDigest.getInstance("SHA-256"); for(Object v:values){d.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));d.update((byte)0);}return HexFormat.of().formatHex(d.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);} }
    private <T> T execute(io.infranexum.core.events.TransactionalWork<T> work) {
        try { return events.execute(work).value(); }
        catch (TransactionExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof FacilityConflictException conflict) throw conflict;
            if (cause instanceof FacilityNotFoundException notFound) throw notFound;
            if (cause instanceof FacilityQuotaException quota) throw quota;
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            throw failure;
        }
    }
    private record Parent(DomainIdentifier scopeId){}
    @FunctionalInterface private interface Transition{FacilityNode apply(FacilityNode current);}
}
