package io.infranexum.organization.application;
import io.infranexum.core.contracts.DomainIdentifier; import java.util.Objects; import java.util.regex.Pattern;
/** Trusted server-side context propagated to organization commands. */
public record OrganizationCommandContext(String actorId,DomainIdentifier correlationId,String idempotencyKey,String reason) {
    private static final Pattern ACTOR=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,159}"); private static final Pattern KEY=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");
    public OrganizationCommandContext { Objects.requireNonNull(actorId,"actorId"); actorId=actorId.strip(); if(!ACTOR.matcher(actorId).matches())throw new IllegalArgumentException("invalid actorId"); Objects.requireNonNull(correlationId,"correlationId"); Objects.requireNonNull(idempotencyKey,"idempotencyKey"); idempotencyKey=idempotencyKey.strip(); if(!KEY.matcher(idempotencyKey).matches())throw new IllegalArgumentException("invalid idempotencyKey"); if(reason!=null){reason=reason.strip(); if(reason.isEmpty()||reason.length()>512||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid reason");} }
}
