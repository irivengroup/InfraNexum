package io.infranexum.organization.domain;
/** Normative subdivision lifecycle. */
public enum SubdivisionState { DRAFT, ACTIVE, INACTIVE, ARCHIVED, DELETED;
    public boolean canTransitionTo(SubdivisionState target){ if(target==null)return false; return switch(this){case DRAFT->target==ACTIVE; case ACTIVE->target==INACTIVE; case INACTIVE->target==ACTIVE||target==ARCHIVED; case ARCHIVED->target==DELETED; case DELETED->false;};}
}
