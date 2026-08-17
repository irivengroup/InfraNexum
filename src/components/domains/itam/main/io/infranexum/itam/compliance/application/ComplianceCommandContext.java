package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Authenticated mutation context shared by HTTP and CLI compliance use cases. */
public record ComplianceCommandContext(DomainIdentifier actorId,DomainIdentifier correlationId,String idempotencyKey,String reason) {
    public ComplianceCommandContext {
        Objects.requireNonNull(actorId,"actorId");Objects.requireNonNull(correlationId,"correlationId");
        idempotencyKey=text(idempotencyKey,"idempotencyKey",8,200);reason=text(reason,"reason",2,1024);
    }
    private static String text(String value,String field,int min,int max){Objects.requireNonNull(value,field);if(value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid "+field);String r=value.strip();if(r.length()<min||r.length()>max)throw new IllegalArgumentException("invalid "+field);return r;}
}
