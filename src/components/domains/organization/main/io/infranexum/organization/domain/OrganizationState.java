package io.infranexum.organization.domain;

/** Normative organization lifecycle states. */
public enum OrganizationState {
    PROVISIONING, ACTIVE, SUSPENDED, ARCHIVING, ARCHIVED, DELETION_PENDING, DELETED;

    public boolean canTransitionTo(OrganizationState target) {
        if (target == null) return false;
        return switch (this) {
            case PROVISIONING -> target == ACTIVE || target == DELETION_PENDING;
            case ACTIVE -> target == SUSPENDED || target == ARCHIVING;
            case SUSPENDED -> target == ACTIVE || target == ARCHIVING;
            case ARCHIVING -> target == ARCHIVED;
            case ARCHIVED -> target == DELETION_PENDING;
            case DELETION_PENDING -> target == DELETED;
            case DELETED -> false;
        };
    }
}
