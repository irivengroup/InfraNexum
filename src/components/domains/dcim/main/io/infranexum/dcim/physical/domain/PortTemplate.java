package io.infranexum.dcim.physical.domain;

import java.util.Locale;
import java.util.Objects;

/** Immutable declarative port template belonging to a multi-vendor equipment model. */
public record PortTemplate(String namePrefix, int count, PortKind kind, String media, String connector) {
    public PortTemplate {
        namePrefix = text(namePrefix,"namePrefix",1,24);
        if(count < 1 || count > 512) throw new IllegalArgumentException("port template count must be 1..512");
        Objects.requireNonNull(kind,"kind");
        media = token(media,"media"); connector = token(connector,"connector");
    }
    public String portName(int index){ if(index<1||index>count)throw new IllegalArgumentException("port index outside template"); return namePrefix+index; }
    private static String token(String value,String field){String v=text(value,field,1,32).toLowerCase(Locale.ROOT);if(!v.matches("[a-z0-9][a-z0-9._-]{0,31}"))throw new IllegalArgumentException(field+" is invalid");return v;}
    private static String text(String value,String field,int min,int max){Objects.requireNonNull(value,field);if(value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(field+" invalid");String v=value.strip();if(v.length()<min||v.length()>max)throw new IllegalArgumentException(field+" length/content is invalid");return v;}
}
