package io.infranexum.dcim.physical.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Concrete physical port instantiated from an EquipmentModel template. */
public record PhysicalPort(DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier equipmentId, String name, PortKind kind, String media, String connector, boolean connected) {
    public PhysicalPort { Objects.requireNonNull(id,"id");Objects.requireNonNull(organizationId,"organizationId");Objects.requireNonNull(equipmentId,"equipmentId");name=text(name,"name",1,64);Objects.requireNonNull(kind,"kind");media=text(media,"media",1,32).toLowerCase();connector=text(connector,"connector",1,32).toLowerCase(); }
    private static String text(String v,String f,int min,int max){Objects.requireNonNull(v,f);if(v.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(f+" invalid");String x=v.strip();if(x.length()<min||x.length()>max)throw new IllegalArgumentException(f+" invalid");return x;}
}
