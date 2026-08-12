package io.infranexum.organization.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant; import java.util.Objects;

/** Immutable subdivision aggregate constrained to exactly one owning organization. */
public final class Subdivision {
    private final DomainIdentifier id, organizationId, parentSubdivisionId; private final SubdivisionCode code;
    private final String displayName, description; private final SubdivisionType type; private final SubdivisionState state;
    private final long version; private final Instant createdAt, updatedAt, deletedAt;
    private Subdivision(DomainIdentifier id, DomainIdentifier organizationId, SubdivisionCode code, String displayName, String description,
            SubdivisionType type, SubdivisionState state, DomainIdentifier parentSubdivisionId, long version, Instant createdAt, Instant updatedAt, Instant deletedAt){
        this.id=Objects.requireNonNull(id,"id"); this.organizationId=Objects.requireNonNull(organizationId,"organizationId"); this.code=Objects.requireNonNull(code,"code");
        this.displayName=text(displayName,"displayName",2,160,false); this.description=text(description,"description",0,4000,true); this.type=Objects.requireNonNull(type,"type"); this.state=Objects.requireNonNull(state,"state");
        if(parentSubdivisionId!=null && parentSubdivisionId.equals(id)) throw new IllegalArgumentException("subdivision cannot parent itself"); this.parentSubdivisionId=parentSubdivisionId;
        if(version<0) throw new IllegalArgumentException("version must be non-negative"); this.version=version; this.createdAt=Objects.requireNonNull(createdAt,"createdAt"); this.updatedAt=Objects.requireNonNull(updatedAt,"updatedAt");
        if(updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt"); this.deletedAt=deletedAt; if(state==SubdivisionState.DELETED && deletedAt==null) throw new IllegalArgumentException("deleted subdivision requires deletedAt");
    }
    public static Subdivision active(DomainIdentifier id,DomainIdentifier organizationId,SubdivisionCode code,String displayName,String description,SubdivisionType type,DomainIdentifier parent,Instant now){return new Subdivision(id,organizationId,code,displayName,description,type,SubdivisionState.ACTIVE,parent,0,now,now,null);}
    public static Subdivision restore(DomainIdentifier id,DomainIdentifier organizationId,SubdivisionCode code,String displayName,String description,SubdivisionType type,SubdivisionState state,DomainIdentifier parent,long version,Instant createdAt,Instant updatedAt,Instant deletedAt){return new Subdivision(id,organizationId,code,displayName,description,type,state,parent,version,createdAt,updatedAt,deletedAt);}
    public Subdivision deactivate(Instant now){return transition(SubdivisionState.INACTIVE,now);} public Subdivision reactivate(Instant now){return transition(SubdivisionState.ACTIVE,now);} public Subdivision archive(Instant now){return transition(SubdivisionState.ARCHIVED,now);} public Subdivision delete(Instant now){return transition(SubdivisionState.DELETED,now);}
    private Subdivision transition(SubdivisionState target,Instant now){Objects.requireNonNull(now,"now"); if(!state.canTransitionTo(target))throw new IllegalStateException("invalid subdivision state transition: "+state+" -> "+target); if(now.isBefore(updatedAt))throw new IllegalArgumentException("transition time precedes current state"); return new Subdivision(id,organizationId,code,displayName,description,type,target,parentSubdivisionId,Math.addExact(version,1),createdAt,now,target==SubdivisionState.DELETED?now:deletedAt);}
    private static String text(String value,String field,int min,int max,boolean optional){if(value==null){if(optional)return null;throw new NullPointerException(field);}String v=value.strip();if(optional&&v.isEmpty())return null;if(v.length()<min||v.length()>max||v.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid "+field);return v;}
    public DomainIdentifier id(){return id;} public DomainIdentifier organizationId(){return organizationId;} public SubdivisionCode code(){return code;} public String displayName(){return displayName;} public String description(){return description;} public SubdivisionType type(){return type;} public SubdivisionState state(){return state;} public DomainIdentifier parentSubdivisionId(){return parentSubdivisionId;} public long version(){return version;} public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;} public Instant deletedAt(){return deletedAt;}
}
