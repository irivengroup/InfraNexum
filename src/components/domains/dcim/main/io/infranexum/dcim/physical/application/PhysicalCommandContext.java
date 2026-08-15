package io.infranexum.dcim.physical.application;
import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;
/** Authenticated command metadata used for audit, events and idempotency correlation. */
public record PhysicalCommandContext(DomainIdentifier actorId,DomainIdentifier correlationId,String reason){ public PhysicalCommandContext{Objects.requireNonNull(actorId,"actorId");Objects.requireNonNull(correlationId,"correlationId");reason=Objects.requireNonNull(reason,"reason").strip();if(reason.length()<2||reason.length()>1024)throw new IllegalArgumentException("reason must be 2..1024 characters");}}
